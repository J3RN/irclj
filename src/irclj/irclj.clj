(ns   
    #^{:author "Anthony Simpson (Rayne)",
       :doc "A small IRC library to abstract over lower-level IRC connection handling."} 
    irclj.irclj
    (:use clojure.contrib.io)
    (:import [java.io PrintStream PrintWriter BufferedReader InputStreamReader]
	     java.net.Socket))

(defrecord IRC [name password server username port realname fnmap])

(defn create-bot [{:keys [name password server username port realname fnmap]
		   :or {name "irclj" username "irclj" realname "teh bawt"
			port 6667}}]
  (IRC. name password server username port realname fnmap))

(defn mess-to-map
  ""
  [[user doing & more]]
  (let [[nick ident hostmask] (.split user "\\!|\\@")
	message-map {:user user
		     :nick nick
		     :ident ident
		     :doing doing}]
    (merge message-map 
	   (condp = doing
	     "PRIVMSG" {:channel (first more) :message (->> more second rest butlast (apply str))}
	     "QUIT" {:reason (apply str (rest more))}
	     "JOIN" {:channel (apply str (rest more))}
	     "PART" {:channel (first more) :reason (apply str (rest (second more)))}
	     "NOTICE" {:target (first more) :message (->> more second rest butlast (apply str))}
	     "MODE" {:channel (first more) :mode (second more) :user (last more)}
	     {}))))

(defn handle [& more])

(defn close
  "Closes an IRC connection."
  [{:keys [sock sockin sockout]}]
  (.println sockout "QUIT")
  (.close sock)
  (.close sockin)
  (.close sockout))

(defn connect
  ""
  [#^IRC {:keys [name password server username port realname fnmap server port] :as botmap}
   & {channels :channels}]
  (let [sock (Socket. server port)
	sockout (PrintWriter. (output-stream sock) true)
	sockin (reader (input-stream sock))]
    (doto sockout
      (.println (str "NICK " name))
      (.println (str "USER " username " na na :" realname)))
    (.start (Thread. (fn []
		       (while (not (.isClosed sock))
			      (let [rline (try (.readLine sockin) 
					       (catch java.net.SocketException _ "Socket Closed."))
				    line (apply str (rest rline))
				    words (.split line " ")]
				(println rline)
				(cond
				 (.startsWith rline "PING") (.println sockout (.replace rline "PING" "PONG"))
				 (= (second words) "001") (doseq [channel channels] 
							    (.println sockout (str "JOIN " channel))))
				:else (handle (mess-to-map words) fnmap))))))
    (assoc botmap :sock sock :sockin sockin :sockout sockout)))


(def bot (create-bot {:name "ircljbot" :server "irc.freenode.net"}))
(def newbot (connect bot :channels ["#()"]))
(read-line)
(close newbot)