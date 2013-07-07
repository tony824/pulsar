 ; Pulsar: lightweight threads and Erlang-like actors for Clojure.
 ; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 ;
 ; This program and the accompanying materials are dual-licensed under
 ; either the terms of the Eclipse Public License v1.0 as published by
 ; the Eclipse Foundation
 ;
 ;   or (per the licensee's choosing)
 ;
 ; under the terms of the GNU Lesser General Public License version 3.0
 ; as published by the Free Software Foundation.

;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar.core
  "Pulsar is an implementation of lightweight threads (fibers),
  Go-like channles and Erlang-like actors for the JVM"
(:refer-clojure :exclude [promise])
(:import [java.util.concurrent TimeUnit ExecutionException TimeoutException Future]
         [jsr166e ForkJoinPool ForkJoinTask]
         [co.paralleluniverse.strands Strand Stranded]
         [co.paralleluniverse.strands SuspendableCallable]
         [co.paralleluniverse.fibers DefaultFiberPool Fiber Joinable FiberUtil]
         [co.paralleluniverse.fibers.instrument]
         [co.paralleluniverse.strands.channels Channel Channels Channels$OverflowPolicy ReceivePort SendPort 
          Selectable Selector SelectAction
          TickerChannelConsumer Topic ReceivePortGroup
          IntChannel LongChannel FloatChannel DoubleChannel
          IntSendPort LongSendPort FloatSendPort DoubleSendPort
          IntReceivePort LongReceivePort FloatReceivePort DoubleReceivePort
          DelayedVal]
         [co.paralleluniverse.pulsar ClojureHelper ChannelsHelper]
         ; for types:
         [clojure.lang Keyword Sequential IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
(:require [co.paralleluniverse.pulsar.interop :refer :all]
          [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

;; ## clojure.core type annotations

(ann clojure.core/split-at (All [x] (Fn [Long (IPersistentCollection x) -> (IPersistentVector (IPersistentCollection x))])))
(ann clojure.core/coll? [Any -> Boolean :filters {:then (is (IPersistentCollection Any) 0) :else (! (IPersistentCollection Any) 0)}])
(ann clojure.core/partition-all (All [x] (Fn [Long (ISeq x) -> (ISeq (U (ISeq x) x))])))
(ann clojure.core/into (All [[xs :< (IPersistentCollection Any)]] (Fn [xs (IPersistentCollection Any) -> xs])))
(ann clojure.core/set-agent-send-executor! [java.util.concurrent.ExecutorService -> nil])
(ann clojure.core/set-agent-send-off-executor! [java.util.concurrent.ExecutorService -> nil])

;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

(defmacro dbg [& body]
  {:no-doc true}
  `(let [x# ~@body
         y#    (if (seq? x#) (take 20 x#) x#)
         more# (if (seq? x#) (nthnext x# 20) false)]
     (println "dbg:" '~@body "=" y# (if more# "..." ""))
     x#))

;; from core.clj:
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(ann sequentialize (All [x y]
                        (Fn
                         [(Fn [x -> y]) ->
                          (Fn [x -> y]
                              [(ISeq x) -> (ISeq y)]
                              [x * -> (ISeq y)])])))
(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (seq? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

;;     (surround-with nil 4 5 6) -> (4 5 6)
;;     (surround-with '(1 2 3) 4 5 6) -> ((1 2 3 4 5 6))
;;     (surround-with '(1 (2)) '(3 4)) -> ((1 (2) (3 4)))
(ann surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn surround-with
  [expr & exprs]
  (if (nil? expr)
    exprs
    (list (concat expr exprs))))

;;     (deep-surround-with '(1 2 3) 4 5 6) -> (1 2 3 4 5 6)
;;     (deep-surround-with '(1 2 (3)) 4 5 6) -> (1 2 (3 4 5 6))
;;     (deep-surround-with '(1 2 (3 (4))) 5 6 7) -> (1 2 (3 (4 5 6 7)))
(ann ^:nocheck deep-surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn- deep-surround-with
  [expr & exprs]
  (if (not (coll? (last expr)))
    (concat expr exprs)
    (concat (butlast expr) (list (apply deep-surround-with (cons (last expr) exprs))))))

(ann ops-args [(ISeq (Vector* (Fn [Any -> Boolean]) Any)) (ISeq Any) -> (ISeq Any)])
(defn- ops-args
  [pds xs]
  "Used to simplify optional parameters in functions.
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)]
      (if (p? x)
        (cons x (ops-args (rest pds) (rest xs)))
        (cons d (ops-args (rest pds) xs))))
    (seq xs)))

(ann ^:nocheck kps-args [(ISeq Any) -> (Vector* (ISeq Any) (ISeq Any))])
(defn kps-args
  {:no-doc true}
  [args]
  (let [aps (partition-all 2 args)
        [opts-and-vals ps] (split-with #(keyword? (first %)) aps)
        options (into {} (map vec opts-and-vals))
        positionals (reduce into [] ps)]
    [options positionals]))

(ann extract-keys [(ISeq Keyword) (ISeq Any) -> (Vector* (ISeq Any) (ISeq Any))])
(defn- extract-keys
  [ks pargs]
  (if (not (seq ks))
    [[] pargs]
    (let [[k ps] (split-with #(= (first ks) (first %)) pargs)
          [rks rpargs] (extract-keys (next ks) ps)]
      [(vec (cons (first k) rks)) rpargs])))

(ann merge-meta (All [[x :< clojure.lang.IObj] [y :< (IPersistentMap Keyword Any)]]
                     [x y -> (I x (IMeta y))]))
(defn merge-meta
  {:no-doc true}
  [s m]
  (with-meta s (merge-with #(%1) m (meta s))))

(defn apply-variadic
  "Calls a variadic function by applying a concat of all arguments with the last argument (which is supposedly a collection)"
  {:no-doc true}
  [f & args]
  (apply f (concat (butlast args) (last args))))

(ann as-timeunit [Keyword -> TimeUnit])
(defn ^TimeUnit as-timeunit
  "Converts a keyword to a java.util.concurrent.TimeUnit
  <pre>
  :nanoseconds | :nanos | :ns   -> TimeUnit/NANOSECONDS
  :microseconds | :us           -> TimeUnit/MICROSECONDS
  :milliseconds | :millis | :ms -> TimeUnit/MILLISECONDS
  :seconds | :sec               -> TimeUnit/SECONDS
  :minutes | :mins              -> TimeUnit/MINUTES
  :hours | :hrs                 -> TimeUnit/HOURS
  :days                         -> TimeUnit/DAYS
  </pre>
  "
  [x]
  (case x
    (:nanoseconds :nanos :ns)   TimeUnit/NANOSECONDS
    (:microseconds :us)         TimeUnit/MICROSECONDS
    (:milliseconds :millis :ms) TimeUnit/MILLISECONDS
    (:seconds :sec)             TimeUnit/SECONDS
    (:minutes :mins)            TimeUnit/MINUTES
    (:hours :hrs)               TimeUnit/HOURS
    :days                       TimeUnit/DAYS))

(ann ->timeunit [(U TimeUnit Keyword) -> TimeUnit])
(defn ^TimeUnit ->timeunit
  [x]
  (if (instance? TimeUnit x)
    x
    (as-timeunit x)))

(defn convert-duration
  [x from-unit to-unit]
  (.convert (->timeunit to-unit) x (->timeunit from-unit)))

(ann tagged-tuple? [Any -> Boolean])
(defn tagged-tuple?
  [x]
  (and (vector? x) (keyword? (first x))))

(ann unwrap-exception* [Throwable -> Throwable])
(defn unwrap-exception*
  {:no-doc true}
  [^Throwable e]
  (if
    (or (instance? ExecutionException e)
        (and (= (.getClass e) RuntimeException) (.getCause e)))
    (unwrap-exception* (.getCause e))
    e))

(defmacro unwrap-exception
  {:no-doc true}
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (throw (unwrap-exception* e#)))))

;; ## Fork/Join Pool

(ann in-fj-pool? [-> Boolean])
(defn- in-fj-pool?
  "Returns true if we're running inside a fork/join pool; false otherwise."
  []
  (ForkJoinTask/inForkJoinPool))

(ann current-fj-pool [-> ForkJoinPool])
(defn- ^ForkJoinPool current-fj-pool
  "Returns the fork/join pool we're running in; nil if we're not in a fork/join pool."
  []
  (ForkJoinTask/getPool))

(ann make-fj-pool [AnyInteger Boolean -> ForkJoinPool])
(defn ^ForkJoinPool make-fj-pool
  "Creates a new ForkJoinPool with the given parallelism and with the given async mode"
  [^Integer parallelism ^Boolean async]
  (ForkJoinPool. parallelism jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil async))

(ann fj-pool ForkJoinPool)
(def fj-pool
  "A global fork/join pool. The pool uses all available processors and runs in the async mode."
  (DefaultFiberPool/getInstance))

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(ann suspendable? [IFn -> Boolean])
(defn suspendable?
  "Returns true of a function has been instrumented as suspendable; false otherwise."
  [f]
  (or (instance? co.paralleluniverse.pulsar.IInstrumented f)
      (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented)))

(ann suspendable! (Fn [IFn -> IFn]
                      [IFn * -> (ISeq IFn)]
                      [(ISeq IFn) -> (ISeq IFn)]))
(defn suspendable!
  "Makes a function suspendable"
  ([f]
   (ClojureHelper/retransform f nil))
  ([x prot]
   (ClojureHelper/retransform x prot)))

(ann ->suspendable-callable [[Any -> Any] -> SuspendableCallable])
(defn ^SuspendableCallable ->suspendable-callable
  "wrap a clojure function as a SuspendableCallable"
  {:no-doc true}
  [f]
  (ClojureHelper/asSuspendableCallable f))

(defmacro susfn
  "Creates a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(suspendable! (fn ~@expr)))

(defmacro defsusfn
  "Defines a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(do
     (defn ~@expr)
     (def ~(first expr) (suspendable! ~(first expr)))))

(ann ^:nocheck strampoline (All [v1 v2 ...]
                                (Fn
                                 [(Fn [v1 v2 ... v2 -> Any]) v1 v2 ... v2 -> Any]
                                 [[-> Any] -> Any])))
(defsusfn strampoline
  "A suspendable version of trampoline. Should be used to implement
  finite-state-machine actors.

  trampoline can be used to convert algorithms requiring mutual
  recursion without stack consumption. Calls f with supplied args, if
  any. If f returns a fn, calls that fn with no arguments, and
  continues to repeat, until the return value is not a fn, then
  returns that non-fn value. Note that if you want to return a fn as a
  final value, you must wrap it in some data structure and unpack it
  after trampoline returns."
  ([f]
     (let [ret (f)]
       (if (fn? ret)
         (recur ret)
         ret)))
  ([f & args]
     (strampoline #(apply f args))))

;; ## Fibers

(ann get-pool [-> ForkJoinPool])
(defn ^ForkJoinPool get-pool
  {:no-doc true}
  [^ForkJoinPool pool]
  (or pool (current-fj-pool) fj-pool))

(ann fiber [String ForkJoinPool AnyInteger [Any -> Any] -> Fiber])
(defn ^Fiber fiber
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  [& args]
  (let [[^String name ^ForkJoinPool pool ^Integer stacksize f] (ops-args [[string? nil] [#(instance? ForkJoinPool %) fj-pool] [integer? -1]] args)]
    (Fiber. name (get-pool pool) (int stacksize) (->suspendable-callable f))))

(ann start [Fiber -> Fiber])
(defn start
  "Starts a fiber"
  [^Fiber fiber]
  (.start fiber))

(defmacro spawn-fiber
  "Creates and starts a new fiber"
  [& args]
  (let [[{:keys [^String name ^Integer stack-size ^ForkJoinPool pool] :or {stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~@body))))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) (->suspendable-callable f#))]
       (.start fiber#))))

(ann current-fiber [-> Fiber])
(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))

(ann current-fiber [-> Fiber])
(defn fiber->future
  "Takes a spawned fiber yields a future object that will
  invoke the function in another thread, and will cache the result and
  return it on all subsequent calls to deref/@. If the computation has
  not yet finished, calls to deref/@ will block, unless the variant
  of deref with timeout is used. See also - realized?."
  [f]
  (let [^Future fut (FiberUtil/toFuture f)]
    (reify
      clojure.lang.IDeref
      (deref [_] (.get fut))
      clojure.lang.IBlockingDeref
      (deref
       [_ timeout-ms timeout-val]
       (try (.get fut timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
      clojure.lang.IPending
      (isRealized [_] (.isDone fut))
      Future
      (get [_] (.get fut))
      (get [_ timeout unit] (.get fut timeout unit))
      (isCancelled [_] (.isCancelled fut))
      (isDone [_] (.isDone fut))
      (cancel [_ interrupt?] (.cancel fut interrupt?)))))

;; ## Strands
;; A strand is either a thread or a fiber.

(ann current-strand [-> Strand])
(defn ^Strand current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))

(ann alive? [Strand -> Boolean])
(defn alive?
  "Tests whether or not a strand is alive. A strand is alive if it has been started but has not yet died."
  [^Strand a]
  (.isAlive a))

(ann get-strand [Stranded -> Strand])
(defn get-strand
  [^Stranded x]
  (.getStrand x))

(defn spawn-thread
  "Creates and starts a new thread"
  [& args]
  (let [[{:keys [^String name]} body] (kps-args args)]
    (let [f      (if (== (count body) 1) (first body) (fn [] (apply (first body) (rest body))))
          thread (if name (Thread. ^Runnable f name) (Thread. ^Runnable f))]
       (.start thread)
       thread)))

(ann join* [(U Joinable Thread) -> (Option Any)])
(defn- join*
  ([s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s))
     (Strand/join s)))
  ([timeout unit s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s timeout (->timeunit unit)))
     (Strand/join s timeout (->timeunit unit)))))

(ann join (Fn [(U Joinable Thread) -> (Option Any)]
              [(Sequential (U Joinable Thread)) -> (ISeq Any)]))
(defn join
  ([s]
   (if (sequential? s)
     (doall (map join* s))
     (join* s)))
  ([timeout unit s]
   (if (sequential? s)
     (loop [nanos (long (convert-duration timeout unit :nanos))
            res []
            ss s]
       (when (not (pos? nanos))
         (throw (TimeoutException.)))
       (if (seq? ss)
         (let [start (long (System/nanoTime))
               r (join* (first ss) nanos TimeUnit/NANOSECONDS)]
           (recur (- nanos (- (System/nanoTime) start))
                  (conj res r)
                  (rest ss)))
         (seq res)))
     (join* timeout unit s))))

;; ## Promises

(defn promise
  "Returns a promise object that can be read with deref/@, and set,
  once only, with deliver. Calls to deref/@ prior to delivery will
  block, unless the variant of deref with timeout is used. All
  subsequent derefs will return the same delivered value without
  blocking. See also - realized?.

  Unlike clojure.core/promise, this promise object can be used inside Pulsar fibers."
  ([]
   (let [dv (DelayedVal.)]
     (reify
       clojure.lang.IDeref
       (deref [_]
              (.get dv))
       clojure.lang.IBlockingDeref
       (deref
        [_ timeout-ms timeout-val]
        (try
          (.get dv timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
       clojure.lang.IPending
       (isRealized [this]
                   (.isDone dv))
       clojure.lang.IFn
       (invoke
        [this x]
        (.set dv x)
        this))))
  ([f]
   (let [p (promise)]
     (suspendable! f)
     (spawn-fiber #(deliver p (f)))
     p)))

;; ## Channels

(ann channel (Fn [AnyInteger -> Channel]
                 [-> Channel]))
(defn ^Channel channel
  "Creates a channel"
  ([size overflow-policy] (Channels/newChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newChannel (int size)))
  ([]                     (Channels/newChannel 0)))

(defn ^TickerChannelConsumer ticker-consumer
  [^Channel ticker]
  (cond 
    (instance? IntChannel ticker)    (Channels/newTickerConsumerFor ^IntChannel ticker)
    (instance? LongChannel ticker)   (Channels/newTickerConsumerFor ^LongChannel ticker)
    (instance? FloatChannel ticker)  (Channels/newTickerConsumerFor ^FloatChannel ticker)
    (instance? DoubleChannel ticker) (Channels/newTickerConsumerFor ^DoubleChannel ticker)
    :else                            (Channels/newTickerConsumerFor ticker)))

(ann snd (All [x] [Channel x -> x]))
(defn snd
  "Sends a message to a channel"
  [^SendPort channel message]
  (.send channel message))

(ann snd (All [x] [Channel x -> x]))
(defn try-snd
  "Tries to immediately send a message to a channel"
  [^SendPort channel message]
  (.trySend channel message))

(ann rcv (Fn [Channel -> Any]
             [Channel Long (U TimeUnit Keyword) -> (Option Any)]))
(defsusfn rcv
  "Receives a message from a channel or a channel group.
  If a timeout is given, and it expires, rcv returns nil."
  ([^ReceivePort channel]
   (.receive channel))
  ([^ReceivePort channel timeout unit]
   (.receive channel (long timeout) (->timeunit unit))))

(defn try-rcv
  [^ReceivePort channel]
  (.tryReceive channel))

(defn close!
  "Closes a channel"
  [channel]
  (cond
    (instance? SendPort channel)    (.close ^SendPort channel)
    (instance? ReceivePort channel) (.close ^ReceivePort channel)
    :else (throw (IllegalArgumentException. (str (.toString ^Object channel) " is not a channel")))))

(defn closed?
  "Tests whether a channel has been closed and no more messages will be received.
  This function can only be called by the channel's owning strand (the receiver)"
[^ReceivePort channel]
(.isClosed channel))

(defn ^ReceivePort rcv-group
  ([ports]
   (ReceivePortGroup. ports))
  ([port & ports]
   (ReceivePortGroup. (cons port ports))))

(defn topic
  "Creates a new topic."
  []
  (Topic.))

(defn subscribe
  "Subscribes a channel to a topic"
  [^Topic topic ^SendPort channel]
  (.subscribe topic channel))

(defn unsubscribe
  "Unsubscribes a channel from a topic"
  [^Topic topic ^SendPort channel]
  (.unsubscribe topic channel))

(defn ^SelectAction do-sel
  {:no-doc true}
  [ports priority dflt]
  (let [^boolean priority (if priority true false)
        ^java.util.List ps (map (fn [port]
                                  (if (vector? port)
                                    (Selector/send ^SendPort (first port) (second port))
                                    (Selector/receive ^ReceivePort port)))
                                ports)
        ^SelectAction sa (if dflt
                           (Selector/trySelect priority ps)
                           (Selector/select    priority ps))]
    sa))

(defn sel
[ports & {:as opts}]
(let [dflt (contains? opts :default)
      ^SelectAction sa (do-sel ports (:priority opts) dflt)]
  (if (and dflt (nil? sa))
    [(:default opts) :default]
    [(.message sa) (.port sa)])))

(defmacro select
  [& clauses]
  (let [clauses (partition 2 clauses)
        opt? #(keyword? (first %)) 
        opts (filter opt? clauses)
        opts (zipmap (map first opts) (map second opts))
        clauses (remove opt? clauses)
        ports (mapcat #(let [x (first %)] (if (vector? x) x (list x))) clauses)
        exprs (mapcat #(let [x (first %) ; ports
                             e (second %)]; result-expr
                         (if (vector? x) (repeat (count x) e) (list e))) clauses)
        priority (:priority opts)
        dflt (contains? opts :default)
        sa (gensym "sa")]
    `(let [^co.paralleluniverse.strands.channels.SelectAction ~sa
           (do-sel (list ~@ports) ~priority ~dflt)]
       ~@(surround-with (when dflt
                   `(if (nil? ~sa) ~(:default opts)))
                 `(case (.index ~sa)
                    ~@(mapcat 
                        (fn [i e]
                          (let [b (if (and (list? e) (vector? (first e))) (first e) []) ; binding
                                a (if (and (list? e) (vector? (first e))) (rest e)  (list e))] ; action
                            `(~i (let ~(vec (concat (when-let [vr (first b)]  `(~vr (.message ~sa)))
                                                    (when-let [vr (second b)] `(~vr (.port ~sa)))))
                                   ~@a))))
                        (range) exprs))))))

;; ### Primitive channels

(ann int-channel (Fn [AnyInteger -> IntChannel]
                     [-> IntChannel]))
(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size overflow-policy] (Channels/newIntChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newIntChannel (int size)))
  ([]                     (Channels/newIntChannel -1)))

(defmacro snd-int
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendInt ^co.paralleluniverse.strands.channels.IntSendPort ~channel (int ~message)))

(defmacro try-snd-int
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendInt ^co.paralleluniverse.strands.channels.IntSendPort ~channel (int ~message)))

(defmacro rcv-int
  ([channel]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceivePort ~channel)))
  ([channel timeout unit]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann long-channel (Fn [AnyInteger -> LongChannel]
                      [-> LongChannel]))
(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size overflow-policy] (Channels/newLongChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newLongChannel (int size)))
  ([]                     (Channels/newLongChannel -1)))

(defmacro snd-long
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ^co.paralleluniverse.strands.channels.LongSendPort ~channel (long ~message)))

(defmacro stry-nd-long
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendLong ^co.paralleluniverse.strands.channels.LongSendPort ~channel (long ~message)))

(defmacro rcv-long
  ([channel]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceivePort ~channel)))
  ([channel timeout unit]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann float-channel (Fn [AnyInteger -> FloatChannel]
                       [-> FloatChannel]))
(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size overflow-policy] (Channels/newFloatChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newFloatChannel (int size)))
  ([]                     (Channels/newFloatChannel -1)))

(defmacro snd-float
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendFloat ^co.paralleluniverse.strands.channels.FloatSendPort ~channel (float ~message)))

(defmacro try-snd-float
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendFloat ^co.paralleluniverse.strands.channels.FloatSendPort ~channel (float ~message)))

(defmacro rcv-float
  ([channel]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceivePort ~channel)))
  ([channel timeout unit]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann double-channel (Fn [AnyInteger -> DoubleChannel]
                        [-> DoubleChannel]))
(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size overflow-policy] (Channels/newDoubleChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newDoubleChannel (int size)))
  ([]                     (Channels/newDoubleChannel -1)))

(defmacro snd-double
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendDouble ^co.paralleluniverse.strands.channels.DoubleSendPort ~channel (double ~message)))

(defmacro try-snd-double
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendDouble ^co.paralleluniverse.strands.channels.DoubleSendPort ~channel (double ~message)))

(defmacro rcv-double
  ([channel]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceivePort ~channel)))
  ([channel timeout unit]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))


