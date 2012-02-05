# modelcouch

A Clojure CRUD function generator for a given document model spec and CouchDB ([clutch](http://github.com/clojure-clutch/clutch)) connection URL.

The following functions (incl. documentation) are created in the calling namespace:

* `make-typename`
* `get-typename`
* `put-typename`
* `delete-typename`
* `valid-typename?`
* `typename-exists?`

## Model spec

The model spec itself can be given as map or individual key value pairs:

* `:fields` A vector of fieldnames (keywords) for the model entity.
* `:required` A vector of required fieldnames. These must have non-nil and non-empty values or else won't be sent to CouchDB.
* `:validators` A map of vectors of validator fn's for each field to be validated. Each fn takes a single arg representing the field value and must return a truthy result if valid.

The spec also supports the following lifecycle hooks:

* `:on-init` A fn taking the pre-built map constructed by `make-typename` to allow for further customization/initialization.
* `:on-put` A single arg fn executed just before a CouchDB document is being written (and before being checked for presence of required fields).
* `:on-delete` A single arg fn executed after a document has been successfully deleted from CouchDB (arg map will only contain `:id` and `:rev` fields)
* `:on-validate-error` A three arg fn called when validation for a field failed. The arguments are: field value, field name and message

## Usage

Using [leiningen](http://github.com/technomancy/leiningen), add the following dependencies to your project:

```
[com.postspectacular/modelcouch "0.1.0"]
```

The example below defines a simple user entity and uses some features of [Noir](http://webnoir.org) to produce a secure password and define validation functions:

```clojure
(ns myproject.models.user
  (:require
    [noir.util.crypt :as crypto]
    [noir.validation :as vali])
  (:use
    [com.postspectacular.modelcouch]))

(declare valid-user?)

(defcouchdoc "http://localhost:5984/my-database" 'user
  :fields [:type :username :name :email :password :salt]
  :required [:type :username :name :email :password :salt]
  :on-init (fn[_] (assoc _ :type "user"))
  :on-put (fn[_]
            (if (and (valid-user? _) (nil? (:salt _)))
              (let [salt (crypto/gen-salt)
                    pwd (crypto/encrypt salt (:password _))]
                (assoc _ :salt salt :password pwd))
              _))
  :on-validate-error (fn[user field msg] (vali/set-error field msg))
  :validators {
     :username [
        [(fn[_] (re-matches #"[a-zA-Z0-9]{4,15}" _))
         "must be alphanumeric (4-15 characters)"]]
     :name [
        [(fn[_] (re-matches #"[a-zA-Z\s\-]{2,30}" _))
         "must only contain letters or dashes (2-30 characters)"]]
     :password [
        [(fn[_] (vali/min-length? _ 8))
         "must have at least 8 characters"]]
     :email [
        [(fn[_] (vali/is-email? _))
         "must be a valid email address"]]
     })
```

To use the generated functions:

```clojure
(def toxi
  (make-user :username "toxi"
             :name "Karsten Schmidt"
             :email "me@nospam.com"
             :password "fooyakasha"))

(valid-user? toxi)
(put-user toxi)
```

## License

Copyright (C) 2012 Karsten Schmidt

Distributed under the Eclipse Public License, the same as Clojure.