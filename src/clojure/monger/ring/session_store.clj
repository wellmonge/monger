;; Copyright (c) 2011-2014 Michael S. Klishin
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns monger.ring.session-store
  (:require [ring.middleware.session.store :as ringstore]
            [monger.collection             :as mc]
            [monger.core                   :as mg]
            [monger.conversion :refer :all])
  (:import [java.util UUID Date]
           [com.mongodb DB]
           ring.middleware.session.store.SessionStore))

;;
;; Implementation
;;

(def ^{:const true}
  default-session-store-collection "web_sessions")




;;
;; API
;;

;; this session store stores Clojure data structures using Clojure reader. It will correctly store every
;; data structure Clojure reader can serialize and read but won't make the data useful to applications
;; in other languages.

(defrecord ClojureReaderBasedMongoDBSessionStore [^DB db ^String collection-name])

(defmethod print-dup java.util.Date
  [^java.util.Date d ^java.io.Writer out]
  (.write out
          (str "#="
               `(java.util.Date. ~(.getYear d)
                                 ~(.getMonth d)
                                 ~(.getDate d)
                                 ~(.getHours d)
                                 ~(.getMinutes d)
                                 ~(.getSeconds d)))))

(defmethod print-dup org.bson.types.ObjectId
  [oid ^java.io.Writer out]
  (.write out
          (str "#="
               `(org.bson.types.ObjectId. ~(str oid)))))


(extend-protocol ringstore/SessionStore
  ClojureReaderBasedMongoDBSessionStore

  (read-session [store key]
    (if key
      (if-let [m (mc/find-one-as-map (.db store) (.collection-name store) {:_id key})]
        (read-string (:value m))
        {})
      {}))

  (write-session [store key data]
    (let [date  (Date.)
          key   (or key (str (UUID/randomUUID)))
          value (binding [*print-dup* true]
                  (pr-str (assoc data :_id key)))]
      (mc/save (.db store) (.collection-name store) {:_id key :value value :date date})
      key))

  (delete-session [store key]
    (mc/remove-by-id (.db store) (.collection-name store) key)
    nil))


(defn session-store
  [^DB db ^String s]
  (ClojureReaderBasedMongoDBSessionStore. db s))


;; this session store won't store namespaced keywords correctly but stores results in a way
;; that applications in other languages can read. DO NOT use it with Friend.

(defrecord MongoDBSessionStore [^DB db ^String collection-name])

(extend-protocol ringstore/SessionStore
  MongoDBSessionStore

  (read-session [store key]
    (if-let [m (and key
                    (mc/find-one-as-map (.db store) (.collection-name store) {:_id key}))]
      m
      {}))

  (write-session [store key data]
    (let [key  (or key (str (UUID/randomUUID)))]
      (mc/save (.db store) (.collection-name store) (assoc data :date (Date.) :_id key))
      key))

  (delete-session [store key]
    (mc/remove-by-id (.db store) (.collection-name store) key)
    nil))


(defn monger-store
  [^DB db ^String s]
  (MongoDBSessionStore. db s))
