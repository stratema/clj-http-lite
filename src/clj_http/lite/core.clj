(ns clj-http.lite.core
  "Core HTTP request/response implementation."
  (:require [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream InputStream IOException]
           [java.net URI URL HttpURLConnection]
           [javax.net.ssl HttpsURLConnection SSLContext TrustManagerFactory]
           [java.security KeyStore]))

(defn parse-headers
  "Takes a URLConnection and returns a map of names to values.

   If a name appears more than once (like `set-cookie`) then the value
   will be a vector containing the values in the order they appeared
   in the headers."
  [conn]
  (loop [i 1 headers {}]
    (let [k (.getHeaderFieldKey ^HttpURLConnection conn i)
          v (.getHeaderField ^HttpURLConnection conn i)]
      (if k
        (recur (inc i) (update-in headers [k] conj v))
        (zipmap (for [k (keys headers)]
                  (.toLowerCase ^String k))
                (for [v (vals headers)]
                  (if (= 1 (count v))
                    (first v)
                    (vec v))))))))

(defn- coerce-body-entity
  "Coerce the http-entity from an HttpResponse to either a byte-array, or a
  stream that closes itself and the connection manager when closed."
  [{:keys [as]} conn]
  (let [ins (try
              (.getInputStream ^HttpURLConnection conn)
              (catch Exception e
                (.getErrorStream ^HttpURLConnection conn)))]
    (if (or (= :stream as) (nil? ins))
      ins
      (with-open [ins ^InputStream ins
                  baos (ByteArrayOutputStream.)]
        (io/copy ins baos)
        (.flush baos)
        (.toByteArray baos)))))

(defn get-connection [url]
  (.openConnection ^URL (URL. url)))

(defn set-trust-store
  [^HttpsURLConnection conn
   {:keys [trust-store trust-store-pass trust-store-type security-protocol]
    :or {trust-store-type "jks" security-protocol "TLS"}}]
  (let [ssl-context (SSLContext/getInstance security-protocol)
        key-store (KeyStore/getInstance trust-store-type)
        trust-manager-factory (TrustManagerFactory/getInstance "SunX509")]
    (.load key-store (io/input-stream
                      (or (io/resource trust-store)
                          (io/file trust-store)))
           (char-array trust-store-pass))
    (.init trust-manager-factory key-store)
    (.init ssl-context nil (.getTrustManagers trust-manager-factory) nil)
    (.setSSLSocketFactory conn (.getSocketFactory ssl-context))
    conn))

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response.

   Note that where Ring uses InputStreams for the request and response bodies,
   the clj-http uses ByteArrays for the bodies."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout
           conn-timeout multipart debug insecure? save-request? follow-redirects
           chunk-size trust-store trust-store-pass] :as req}]
  (let [http-url (str (name scheme) "://" server-name
                      (when server-port (str ":" server-port))
                      uri
                      (when query-string (str "?" query-string)))
        ^HttpURLConnection conn
        (cond-> (get-connection http-url)
          (and (= scheme :https) trust-store trust-store-pass)
          (set-trust-store req))]
    (when (and content-type character-encoding)
      (.setRequestProperty conn "Content-Type" (str content-type
                                                    "; charset="
                                                    character-encoding)))
    (when (and content-type (not character-encoding))
      (.setRequestProperty conn "Content-Type" content-type))
    (doseq [[h v] headers]
      (.setRequestProperty conn h v))
    (when (false? follow-redirects)
      (.setInstanceFollowRedirects conn false))
    (.setRequestMethod conn (.toUpperCase (name request-method)))
    (when body
      (.setDoOutput conn true))
    (when socket-timeout
      (.setReadTimeout conn socket-timeout))
    (when conn-timeout
      (.setConnectTimeout conn conn-timeout))
    (when chunk-size
      (.setChunkedStreamingMode conn chunk-size))
    (.connect conn)
    (when body
      (with-open [out (.getOutputStream conn)]
        (io/copy body out)))
    (merge {:headers (parse-headers conn)
            :status (.getResponseCode conn)
            :body (when-not (= request-method :head)
                    (coerce-body-entity req conn))}
           (when save-request?
             {:request (assoc (dissoc req :save-request?)
                              :http-url http-url)}))))
