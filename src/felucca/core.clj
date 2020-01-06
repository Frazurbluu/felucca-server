(ns felucca.core
  (:require [celtuce.commands :as redis]
            [celtuce.connector :as celtuce]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [ring.adapter.jetty9 :as jetty]
            [taoensso.timbre :refer [debug info warn error]]
            [tick.alpha.api :as t])
  (:import clojure.lang.PersistentArrayMap
           com.fasterxml.jackson.core.JsonParseException
           [java.util.concurrent Executors TimeoutException TimeUnit]
           java.util.Properties
           [org.eclipse.jetty.websocket.api
            CloseException WebSocketAdapter WebSocketException]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utils ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-timeout
  "Runs `body` for `timeout-ms`. Returns the evaluated body if it runs in under
  `timeout-ms`. Otherwise, cancels the body evaluation and returns `:timeout`.

  Make sure to test for `Threat/interrupted` in body so that it doesn't run
  forever."
  [timeout-ms & body]
  `(let [f# (future (do ~@body))
         v# (gensym)
         result# (deref f# ~timeout-ms v#)]
     (if (= v# result#)
       (do
         (future-cancel f#)
         :timeout)
       result#)))

(defn version
  "Returns the version number for a given x or namespace."
  [x]
  (when-let [properties (io/resource (str "META-INF/maven"
                                          "/" (or (namespace x) (name x))
                                          "/" (name x)
                                          "/" "pom.properties"))]
    (with-open [stream (io/input-stream properties)]
      (.getProperty (doto (Properties.) (.load stream)) "version"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def configuration
  {:redis-uri "redis://localhost:6379"
   :websocket-server-port 8080
   :websocket-server-max-idle-time 20000
   :websocket-server-send-timeout 1000
   :websocket-broken-connection-cleaner-period 1000})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Redis ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce redis-server (atom {}))

(defn start-redis-server! []
  (reset! redis-server
          (let [server (celtuce/redis-server (:redis-uri configuration))]
            {:server server
             :proxy (celtuce/commands-sync server)})))

(defn stop-redis-server! []
  (when (:server @redis-server)
    (celtuce/shutdown (:server @redis-server)))
  (swap! redis-server assoc :proxy nil))

(defn reset-redis-server! []
  (stop-redis-server!)
  (start-redis-server!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Game ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete-game-clients! []
  (redis/del (:proxy @redis-server) "clients"))

(defn get-game-clients []
  (redis/smembers (:proxy @redis-server) "clients"))

(defn register-game-client! [{:keys [address connected-at] :as client}]
  (redis/sadd (:proxy @redis-server) "clients" address))

(defn unregister-game-client! [{:keys [address] :as client}]
  (redis/srem (:proxy @redis-server) "clients" address))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket server ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce websocket-server (atom nil))
(defonce websocket-clients (atom {}))
(defonce websocket-errors (atom []))
(defonce websocket-messages (atom []))

(defn websocket-payload-clients []
  {:clients (get-game-clients)})

(defn websocket-dispatch-request [request]
  (websocket-payload-clients))

(defn websocket-unicast [{:keys [ws] :as client} payload]
  (when payload
    (try
      (jetty/send! ws (json/generate-string payload))
      (catch WebSocketException e
        (error e "Failed to send websocket payload" payload))
      (catch Exception e
        (error e "Failed to send websocket payload" payload))
      (catch IllegalArgumentException e
        (error e "Failed to send websocket payload" payload)))))

;; TODO: make this parallel.
(defn websocket-broadcast-to-other-clients! [{:keys [address] :as client} payload]
  (let [other-clients-addresses (remove #{address} (get-game-clients))]
    (when (pos? (count other-clients-addresses))
      (info "Broadcasting to" (count other-clients-addresses) "clients"))
    (doseq [address other-clients-addresses]
      (websocket-unicast (get @websocket-clients address) payload))))

(defmulti websocket-client-address class)

(defmethod websocket-client-address PersistentArrayMap
  [{:keys [address] :as client}]
  address)

(defmethod websocket-client-address WebSocketAdapter
  [ws]
  (let [remote-address (.getRemoteAddress (.getSession ws))]
    (str (.getHostString remote-address)
         (.getPort remote-address))))

(defn register-websocket-client! [ws]
  (let [client-address (websocket-client-address ws)
        client {:address client-address
                :connected-at (t/now)
                :ws ws}]
    (register-game-client! client)
    (websocket-broadcast-to-other-clients! client (websocket-payload-clients))
    (swap! websocket-clients assoc client-address client)))

(defmulti unregister-websocket-client! class)

(defmethod unregister-websocket-client! WebSocketAdapter
  [ws]
  (let [client-address (websocket-client-address ws)
        client {:address client-address
                :ws ws}]
    (unregister-websocket-client! client)))

(defmethod unregister-websocket-client! PersistentArrayMap
  [{:keys [address] :as client}]
  (unregister-game-client! client)
  (websocket-broadcast-to-other-clients! client (websocket-payload-clients))
  (swap! websocket-clients dissoc address))

(defn register-websocket-message! [ws message]
  (swap! websocket-messages conj message))

(defn register-websocket-error! [ws e]
  (swap! websocket-errors conj {:error e
                                :timestamp (t/now)}))

(defn websocket-admin-command? [ws message]
  (= "admin" (s/trim message)))

(defn websocket-valid-request? [request]
  true)

(defn websocket-message-reply [ws message]
  (if (websocket-admin-command? ws message)
    {:felucca-server-version (version 'felucca)
     :websocket-clients (count (get-game-clients))
     :websocket-messages (count @websocket-messages)
     :websocket-errors (count @websocket-errors)}
    (try
      (let [request (json/parse-string message)]
        (when (websocket-valid-request? request)
          (websocket-dispatch-request request)))
      (catch JsonParseException e
        (let [client (websocket-client-address ws)]
          (error "Failed to parse websocket message from" client "as JSON:"
                 (s/trimr (prn-str message))))))))

(defn make-websocket-event
  ([e ws]
   (make-websocket-event e ws {}))
  ([e ws m]
   (let [client-address (websocket-client-address ws)]
     (assoc m
            :websocket-event e
            :client client-address))))

(defn websocket-on-connect [ws]
  (register-websocket-client! ws)
  (debug (make-websocket-event :on-connect ws)))

(defn websocket-on-close [ws status-code reason]
  (unregister-websocket-client! ws)
  (debug (make-websocket-event :on-close ws {:status-code status-code})))

(defn websocket-on-text [ws text]
  (register-websocket-message! ws text)
  (let [timeout (with-timeout (:websocket-server-send-timeout configuration)
                  (if (.isInterrupted (Thread/currentThread))
                    (warn "Websocket unicast thread interrupted")
                    (let [client-address (websocket-client-address ws)
                          client (get @websocket-clients client-address)
                          payload (websocket-message-reply ws text)]
                      (websocket-unicast client payload))))]
    (when (= :timeout timeout)
      (warn "Websocket unicast timeout via thread interrupt")))
  (debug (make-websocket-event :on-text ws {:text text})))

(defn websocket-on-bytes [ws bytes offset length]
  (debug (make-websocket-event :on-bytes ws {:bytes bytes
                                             :offset offset
                                             :length length})))

(defn websocket-on-error [ws e]
  (letfn [(handle-websocket-error! [error-type]
            (register-websocket-error! ws error-type)
            (unregister-websocket-client! ws))]
    (try
      (throw e)
      (catch CloseException e*
        (handle-websocket-error! :websocket-timeout))
      (catch TimeoutException e*
        (handle-websocket-error! :generic-timeout))
      (catch Exception e*
        (handle-websocket-error! :generic-error))
      (finally
        (debug (make-websocket-event :on-error ws))))))

(def websocket-handler
  {:on-connect websocket-on-connect
   :on-close websocket-on-close
   :on-text websocket-on-text
   :on-bytes websocket-on-bytes
   :on-error websocket-on-error})

(defn http-handler [request]
  {:status 200
   :headers {"X-Powered-By" (str "Felucca Server " (version 'felucca))}})

(defn start-websocket-server! []
  (let [{:keys [websocket-server-port
                websocket-server-max-idle-time]} configuration]
    (info (str "Starting server at wss://0.0.0.0:" websocket-server-port "..."))
    (reset! websocket-server (jetty/run-jetty
                              http-handler
                              {:port websocket-server-port
                               :join? false
                               :ws-max-idle-time websocket-server-max-idle-time
                               :websockets {"/" websocket-handler}}))
    (info (str "Server listening at wss://0.0.0.0:" websocket-server-port))))

(defn stop-websocket-server! []
  (when @websocket-server
    (let [{:keys [websocket-server-port
                  websocket-server-max-idle-time]} configuration]
      (info (str "Stopping server at wss://0.0.0.0:" websocket-server-port "..."))
      (jetty/stop-server @websocket-server)
      (info (str "Stopped server at wss://0.0.0.0:" websocket-server-port)))))

(defn reset-websocket-server! []
  (stop-websocket-server!)
  (start-websocket-server!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Websocket broken connection cleaner ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce websocket-broken-connection-cleaner (atom {}))

;; https://github.com/websockets/ws#how-to-detect-and-close-broken-connections
(defn websocket-clean-broken-connections! []
  (doseq [[address client] @websocket-clients]
    (when-not (jetty/connected? (:ws client))
      (warn "Cleaning up broken websocket connection:" address)
      (unregister-websocket-client! client))))

(defn start-websocket-broken-connection-cleaner! []
  (let [executor (Executors/newScheduledThreadPool 1)
        {:keys [websocket-broken-connection-cleaner-period]} configuration]
    (reset! websocket-broken-connection-cleaner
            {:executor executor
             :cleaner (.scheduleAtFixedRate
                       executor
                       (fn websocket-broken-connection-cleaner []
                         (if (.isInterrupted (Thread/currentThread))
                           (error "websocket-broken-connection-cleaner"
                                  "interrupted")
                           (websocket-clean-broken-connections!)))
                       websocket-broken-connection-cleaner-period
                       websocket-broken-connection-cleaner-period
                       TimeUnit/MILLISECONDS)})))

(defn stop-websocket-broken-connection-cleaner! []
  (when (:executor @websocket-broken-connection-cleaner)
    (.shutdownNow (:executor @websocket-broken-connection-cleaner))))

(defn reset-websocket-broken-connection-cleaner! []
  (stop-websocket-broken-connection-cleaner!)
  (start-websocket-broken-connection-cleaner!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-system! []
  (start-redis-server!)
  (start-websocket-server!)
  (start-websocket-broken-connection-cleaner!))

(defn stop-system! []
  (stop-websocket-broken-connection-cleaner!)
  (stop-websocket-server!)
  (stop-redis-server!))

(defn reset-system! []
  (reset-websocket-broken-connection-cleaner!)
  (reset-websocket-server!)
  (reset-redis-server!))

(defn -main [& args]
  (start-system!))
