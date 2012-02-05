(ns ^{:author "Karsten Schmidt"}
     com.postspectacular.modelcouch
  (:require [com.ashafa.clutch :as couch :only [get-document put-document]]))

(def ^{:private true} meta-make
  {:doc
  "Generates an empty map using the fields given in the model spec
  and merges it with the arguments given (either as map or as key value pairs).
  The _id and _rev fields are automatically injected, but have nil values."
  :arglists '([& m])})

(def ^{:private true} meta-get
  {:doc
  "Retrieves the entity with the given id from the CouchDB instance provided to defcouchdoc
  and merges it with the empty document template to provide all fields."
  :arglists '([id])})

(def ^{:private true} meta-put
  {:doc
  "Checks for the presence of required fields and if successful, attempts to
  store the entity document to the CouchDB instance provided to defcouchdoc.
  If the model spec defined an :on-put fn, it is called first. Returns the
  result document from CouchDB (with inserted/updated :_id and :_rev fields)."
  :arglists '([doc])})

(def ^{:private true} meta-delete
  {:doc
  "Attempts to delete the document with the given id from CouchDB. If the model
  spec defined an :on-delete fn, it is called with the result map if the delete
  was successful."
  :arglists '([id])})

(def ^{:private true} meta-exists
  {:doc
  "Returns true if a document with the given id already exists in CouchDB."
  :arglists '([id])})

(def ^{:private true} meta-valid
  {:doc
  "Returns true if the document contains all required fields and all defined
  validators succeed."
  :arglists '([doc])})

(defn defcouchdoc
  "Generates CRUD functions for a given model spec and CouchDB connection (or URL).
  The following functions (incl. documentation) are created in the calling namespace:

  make-typename, get-typename, put-typename, delete-typename,
  valid-typename?, typename-exists?

  The model spec itself can be given as map or individual key value pairs:

  :fields       A vector of fieldnames (keywords) for the model entity.
  :required     A vector of required fieldnames. These must have non-nil and
                non-empty values or else won't be sent to CouchDB.
  :validators   A map of vectors of validator fn's for each field to be validated.
                Each fn takes a single arg representing the field value and
                must return a truthy result if valid.

  The spec also supports the following hooks:

  :on-init           A fn taking the pre-built map constructed by make-typename to
                     allow for further customization/initialization.
  :on-put            A single arg fn executed just before a couchdb document is being
                     written (and before being checked for presence of required fields).
  :on-delete         A single arg fn executed after a document has been successfully
                     deleted from CouchDB (arg map will only contain :id and :rev fields)
  :on-validate-error A three arg fn called when validation for a field failed:
                     The arguments are: document map, field name and error message"
  [db typename & spec]
  (let[spec (if (map? (first spec)) (first spec) (apply hash-map spec))
       {:keys [fields required validators on-init on-put on-delete on-validate-error]} spec
       fields (reduce #(conj %1 %2) [:_id :_rev] fields)
       required (if-not (set? required)
                  (reduce #(conj %1 %2) #{} required))
       empty-type (reduce #(assoc %1 %2 nil) {} fields)
       sym-make (with-meta (symbol (str "make-" typename)) meta-make)
       sym-get (with-meta (symbol (str "get-" typename)) meta-get)
       sym-delete (with-meta (symbol (str "delete-" typename)) meta-delete)
       sym-exists (with-meta (symbol (str typename "-exists?")) meta-exists)
       sym-put (with-meta (symbol (str "put-" typename)) meta-put)
       sym-valid (with-meta (symbol (str "valid-" typename "?")) meta-valid)
       fn-make (fn[& m] (let[m (if (map? (first m)) (first m) (apply hash-map m))
                             m (merge empty-type m)]
                          (if (nil? on-init) m (on-init m))))
       fn-valid (fn [v]
                  (let[state (atom true)]
                    (doseq[f fields]
                      (if (and (contains? required f)
                               (or (nil? (get v f)) (= 0 (count (get v f)))))
                        (do
                          (when-not (nil? on-validate-error)
                            (on-validate-error v f "is a required field"))
                          (reset! state false))
                        (when-not (nil? (get validators f))
                          (doseq[vpair (get validators f)]
                            (when-not ((first vpair) (get v f))
                              (on-validate-error v f (second vpair))
                              (reset! state false))))))
                    @state))
       fn-get (fn [id] (merge empty-type (couch/get-document db id)))
       fn-delete (fn [id] (couch/delete-document db (fn-get id)))
       fn-put (fn [type]
                   (let[type (if-not (nil? on-put) (on-put type) type)
                        type (reduce
                               (fn[acc e] (if-not (nil? (val e)) (conj acc e) acc))
                               {} (select-keys type fields))
                        status (fn-valid type)]
                     (if status (couch/put-document db type) nil)))
       fn-exists (fn [id] (not (nil? (fn-get id))))]
    (intern *ns* sym-make fn-make)
    (intern *ns* sym-get fn-get)
    (if-not (nil? validators) (intern *ns* sym-valid fn-valid))
    (intern *ns* sym-put fn-put)
    (intern *ns* sym-delete fn-delete)
    (intern *ns* sym-exists fn-exists)))
