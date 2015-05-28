(ns eyvind.core
  (:require [eyvind.mmap :as mmap]
            [clojure.java.io :as io])
  (:import
   [java.io DataOutputStream FileOutputStream DataInputStream FileInputStream]
   [java.net InetAddress NetworkInterface]
   [java.nio ByteBuffer ByteOrder]
   [java.security MessageDigest]
   [java.util LinkedHashMap]
   [java.util.zip CRC32]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn lru [^long size]
  (proxy [LinkedHashMap] [size 0.75 true]
    (removeEldestEntry [_]
      (> (count this) size))))

(defn open-log
  ([file]
   (open-log file (* 8 1024) 1024 {}))
  ([file length cache-size opts]
   (-> (merge {:offset 0 :keydir {} :growth-factor 2} opts)
       (assoc :log (mmap/mmap file length) :cache (lru cache-size)))))

(defrecord KeydirEntry [^long ts ^long value-size ^long value-offset])

(defn header ^bytes [^long ts ^long key-size ^long value-size]
  (.array (doto (ByteBuffer/allocate 20)
            (.order (ByteOrder/nativeOrder))
            (.putLong ts)
            (.putInt (int key-size))
            (.putLong value-size))))

(defn maybe-grow-log [{:keys [^eyvind.mmap.MappedFile log ^long offset ^long growth-factor] :as bc} ^long needed]
  (let [length (.length log)]
    (cond-> bc
      (> (+ offset needed) length) (update-in [:log] mmap/remap (* growth-factor length)))))

(defn put-entry
  ([bc ^String k ^bytes v]
   (put-entry bc (System/currentTimeMillis) k v))
  ([bc ^long ts ^String k ^bytes v]
   (let [key-bytes (.getBytes k "UTF-8")
         header-bytes (header ts (count key-bytes) (count v))
         header-offset 8
         entry-size (+ header-offset (count header-bytes) (count key-bytes) (count v))
         {:keys [^eyvind.mmap.MappedFile log ^long offset] :as bc} (maybe-grow-log bc entry-size)
         key-offset (+ header-offset (count header-bytes) offset)
         value-offset (+ key-offset (count key-bytes))
         crc (.getValue (doto (CRC32.)
                          (.update ^bytes header-bytes)
                          (.update key-bytes)
                          (.update v)))]
     (mmap/put-long log offset crc)
     (mmap/put-bytes log header-offset header-bytes)
     (mmap/put-bytes log key-offset key-bytes)
     (mmap/put-bytes log value-offset v)
     (-> bc
         (update-in [:offset] + entry-size)
         (update-in [:keydir] assoc k (->KeydirEntry ts (count v) value-offset))
         (update-in [:cache] #(doto ^LinkedHashMap % (.put k v)))))))

(defn tombstone? [^KeydirEntry entry]
  (zero? (.value-size entry)))

(defn get-entry [{:keys [log keydir ^LinkedHashMap cache] :as bc} k]
  (if (contains? cache k)
    (get cache k)
    (when-let [^KeydirEntry entry (get keydir k)]
      (when-not (tombstone? entry)
        (doto (mmap/get-bytes log (.value-offset entry) (byte-array (.value-size entry)))
          (->> (.put cache k)))))))

(defn remove-entry [bc k]
  (-> bc
      (put-entry k (byte-array 0))
      (update-in [:keydir] dissoc k)
      (update-in [:cache] #(doto ^LinkedHashMap % (.remove k)))))

(defn scan-log [{:keys [log keydir ^long offset] :as bc}]
  (loop [offset offset keydir keydir]
    (let [crc (mmap/get-long log offset)]
      (if (zero? crc)
        (assoc bc :keydir keydir :offset offset)
        (let [ts (mmap/get-long log (+ 8 offset))
              key-size (mmap/get-int log (+ 16 offset))
              value-size (mmap/get-long log (+ 20 offset))
              entry-size (+ 20 key-size value-size)]
          (when-not (= crc (mmap/crc-checksum log (+ 8 offset) entry-size))
            (throw (IllegalStateException. (str "CRC check failed at offset: " offset))))
          (let [key-bytes (mmap/get-bytes log (+ 28 offset) (byte-array key-size))
                k (String. ^bytes key-bytes "UTF-8")
                entry (->KeydirEntry ts value-size (+ offset 28 key-size))]
            (recur (+ offset 28 key-size value-size)
                   (if (tombstone? entry)
                     (dissoc keydir k)
                     (assoc keydir k entry)))))))))

(defn hint-file ^String [{:keys [log]}]
  (str (:file log) ".hint"))

(defn write-hint-file [{:keys [keydir] :as bc}]
  (with-open [out (DataOutputStream. (io/output-stream (hint-file bc)))]
    (doseq [[^String k ^KeydirEntry v] keydir]
      (let [key-bytes (.getBytes k "UTF-8")
            key-size (count key-bytes)]
        (doto out
          (.writeLong (.ts v))
          (.writeInt key-size)
          (.writeLong (.value-size v))
          (.writeLong (.value-offset v))
          (.write key-bytes))))))

(defn read-hint-file [{:keys [log keydir] :as bc}]
  (let [hints (io/file (hint-file bc))]
    (if (.exists hints)
      (with-open [in (DataInputStream. (io/input-stream hints))]
        (loop [offset 0 keydir keydir]
          (if (pos? (.available in))
            (let [ts (.readLong in)
                  key-size (.readInt in)
                  value-size (.readLong in)
                  value-offset (.readLong in)
                  key-bytes (byte-array key-size)]
              (.read in key-bytes)
              (recur (max offset (+ value-offset value-size))
                     (assoc keydir (String. key-bytes "UTF-8") (->KeydirEntry ts value-size value-offset))))
            (assoc bc :keydir keydir :offset offset))))
      bc)))

(defn sha1 [x]
  (->> (doto (MessageDigest/getInstance "SHA-1")
         (.update (-> x str (.getBytes "UTF-8"))))
       .digest
       (BigInteger. 1)))

(defn biginteger->hex [^BigInteger x]
  (format "%040x" x))

(defn hex->biginteger [x]
  (BigInteger. (str x) 16))

(defn ips []
  (->> (NetworkInterface/getNetworkInterfaces)
       enumeration-seq
       (mapcat (comp enumeration-seq #(.getInetAddresses ^NetworkInterface %)))
       (map #(.getHostAddress ^InetAddress %))))

(defn ip []
  (->> (ips)
       (remove (partial re-find #"^127\."))
       first))
