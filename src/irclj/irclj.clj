(ns   
    #^{:author "Anthony Simpson (Rayne)",
       :doc "A small IRC library to abstract over lower-level IRC connection handling."} 
    irclj.irclj
    (:use [clojure.contrib io [string :only [join]]])
    (:import [java.io PrintStream PrintWriter BufferedReader InputStreamReader]
	     java.net.Socket))

(defrecord IRC [name password server username port realname fnmap])

(defn create-bot 
  "Function to create an IRC(bot). You need to at most supply a server and fnmap.
  If you don't supply a name, username, realname, or port, they will default to
  irclj, irclj, teh bawt, and 6667 respectively."
  [{:keys [name password server username port realname fnmap]
    :or {name "irclj" username "irclj" realname "teh bawt"
	 port 6667}}]
  (IRC. name password server username port realname fnmap))

(defn- send-msg
  "Takes an IRC, a message and a target to send it to, and sends an IRC message to
  target."
  [type irc target message]
  (let [{{sockout :sockout} :connection} @irc]
    (.println sockout (str type " " target " :" message)))
  (println (str ">>>" type " " target " :" message)))

(defn send-message
  "Takes an IRC, a message and a target to send it to, and sends an IRC message to
  target."
  [irc target message]
  (send-msg "PRIVMSG" irc target message))

(defn send-notice
  "Takes an IRC, a message, and a target to send to, and sends a NOTICE to target"
  [irc target message]
  (send-msg "NOTICE" irc target message))

(defn send-action
  "Sends a CTCP ACTION to a target"
  [irc target message]
  (send-msg "PRIVMSG" irc target (str \ "ACTION" " " message \)))

(defn set-nick
  "Changes the connectors nick."
  [irc nick]
  (send-msg "NICK" irc nick nil)
  (dosync (alter irc assoc :name nick)))

(defn extract-message [s]
  (apply str (rest (join " " s))))

(defn mess-to-map
  "Parses a message into a map."
  [[user doing & [channel & message :as more]]]
  (let [[nick ident hostmask] (.split user "\\!|\\@")
	message-map {:user user
		     :nick nick
		     :hmask hostmask
		     :ident ident
		     :doing doing}]
    (merge message-map 
	   (condp = doing
	     "PRIVMSG" {:channel channel :message (extract-message message)}
	     "QUIT" {:reason (extract-message more)}
	     "JOIN" {:channel (apply str (rest more))}
	     "PART" {:channel channel :reason (extract-message message)}
	     "NOTICE" {:target channel :message (extract-message message)}
	     "MODE" (let [[mode user] message] {:channel channel :mode mode :user user })
	     {}))))

(defmacro when-not-nil [pred & body]
  `(when-not (nil? ~pred) ~@body))

(defn handle-ctcp
  "Takes a CTCP message and responds to it."
  [irc nick ctcp-s]
  (send-notice 
   irc nick (condp = (apply str (remove #(= \ %) ctcp-s))
	      "VERSION" "irclj version ohai"
	      "TIME"    "Time for you to SHUT THE FUCK UP."
	      "FINGER"  "OMG, DADDY TOUCHED ME IN THE BAD PLACE.!"
	      "Not supported.")))

(defn handle [{:keys [user nick ident doing channel message reason target mode] :as info} irc]
  (let [{{:keys [on-message on-quit on-part on-join on-notice on-mode]} :fnmap} @irc
	info-map (assoc info :irc irc)]
    (condp = doing
      "PRIVMSG" (if (= (first message) \)
		  (handle-ctcp irc nick message)
		  (when-not-nil on-message (on-message 
					    (if (= channel (:name @irc)) 
					      (assoc info-map :channel nick) 
					      info-map))))
      "QUIT" (when-not-nil on-quit (on-quit info-map))
      "JOIN" (when-not-nil on-join (on-join info-map))
      "PART" (when-not-nil on-part (on-part info-map))
      "NOTICE" (when-not-nil on-notice (on-notice info-map))
      "MODE" (when-not-nil on-mode (on-mode info-map))
      nil)))

(defn close
  "Closes an IRC connection (including the socket)."
  [irc]
  (let [{{:keys [sock sockout sockin]} :connection} @irc]
    (.println sockout "QUIT")
    (.close sock)
    (.close sockin)
    (.close sockout)))

(defn connect
  "Takes an IRC defrecord and optionally, a sequence of channels to join and
  connects to IRC based on the information provided in the IRC and optionally joins
  the channels. The connection itself runs in a separate thread, and the input stream
  and output stream are merged into the IRC and returned as a ref."
  [#^IRC {:keys [name password server username port realname fnmap server port] :as botmap}
   & {channels :channels}]
  (let [sock (Socket. server port)
	sockout (PrintWriter. (output-stream sock) true)
	sockin (reader (input-stream sock))
	irc (ref (assoc botmap :connection {:sock sock :sockin sockin :sockout sockout}))]
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
				:else (handle (mess-to-map words) irc))))))
    irc))

(def fnmap {:on-message (fn [{:keys [nick channel message irc]}] 
			  (let [[cmd & more] (.split message " ")] 
			    (condp = cmd
			      "$whoareyou?" (send-action irc channel (:name @irc))
			      "$setnick" (set-nick irc (first more))
			      nil)))
	    :on-quit (fn [{:keys [nick reason irc]}] 
		       (send-message irc "#irclj" (str nick " quit. His reason was: " reason)))
	    :on-part (fn [{:keys [nick reason channel irc]}]
		       (send-message irc channel (str nick " parted. Reason: " reason)))})

(def bot (create-bot {:name "ircljbot" :server "irc.freenode.net" :fnmap fnmap}))
(def newbot (connect bot :channels ["#irclj"]))
(read-line)
(close newbot)