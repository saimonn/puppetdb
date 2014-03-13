(ns com.puppetlabs.puppetdb.examples
  (:use [com.puppetlabs.puppetdb.catalogs :only [catalog-version]]))

(def catalogs
  {:empty
   {:certname         "empty.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
    :version          "1330463884"
    :edges            #{{:source       {:type "Stage" :title "main"}
                         :target       {:type "Class" :title "Settings"}
                         :relationship :contains}
                        {:source       {:type "Stage" :title "main"}
                         :target       {:type "Class" :title "Main"}
                         :relationship :contains}}
    :resources        {{:type "Class" :title "Main"}     {:exported   false
                                                          :title      "Main"
                                                          :tags       #{"class" "main"}
                                                          :type       "Class"
                                                          :parameters {:name "main"}}
                       {:type "Class" :title "Settings"} {:exported false
                                                          :title    "Settings"
                                                          :tags     #{"settings" "class"}
                                                          :file     "/etc/puppet/modules/settings/manifests/init.pp"
                                                          :line     1
                                                          :type     "Class"}
                       {:type "Stage" :title "main"}     {:exported false
                                                          :title    "main"
                                                          :tags     #{"main" "stage"}
                                                          :type     "Stage"}}}

   :basic
   {:certname         "basic.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
    :transaction-uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :environment      "DEV"
    :version          "123456789"
    :edges            #{{:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar"}
                         :relationship :contains}
                        {:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :contains}
                        {:source       {:type "File" :title "/etc/foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :required-by}}
    :resources        {{:type "Class" :title "foobar"}         {:type "Class" :title "foobar" :exported false}
                       {:type "File" :title "/etc/foobar"}     {:type       "File"
                                                                :title      "/etc/foobar"
                                                                :exported   false
                                                                :file       "/tmp/foo"
                                                                :line       10
                                                                :tags       #{"file" "class" "foobar"}
                                                                :parameters {:ensure "directory"
                                                                             :group  "root"
                                                                             :user   "root"}}
                       {:type "File" :title "/etc/foobar/baz"} {:type       "File"
                                                                :title      "/etc/foobar/baz"
                                                                :exported   false
                                                                :file       "/tmp/bar"
                                                                :line       20
                                                                :tags       #{"file" "class" "foobar"}
                                                                :parameters {:ensure  "directory"
                                                                             :group   "root"
                                                                             :user    "root"
                                                                             :require "File[/etc/foobar]"}}}}

   :invalid
   {:certname         "invalid.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
    :transaction-uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :version          123456789
    :edges            #{{:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "does not exist"}
                         :relationship :contains}}
    :resources        {{:type "Class" :title "foobar"}     {:type "Class" :title "foobar" :exported false}
                       {:type "File" :title "/etc/foobar"} {:type       "File"
                                                            :title      "/etc/foobar"
                                                            :exported   false
                                                            :tags       #{"file" "class" "foobar"}
                                                            :parameters {"ensure" "directory"
                                                                         "group"  "root"
                                                                         "user"   "root"}}}}})

(def v1-empty-wire-catalog
  "Basic wire catalog with a minimum number of resources/edges used/modified
   for examples of a catalog"
  {:metadata      {:api_version 1}
   :document_type "Catalog"
   :data
   {:edges
    [{:relationship "contains"
      :target       {:title "Settings" :type "Class"}
      :source       {:title "main" :type "Stage"}}
     {:relationship "contains"
      :target       {:title "main" :type "Class"}
      :source       {:title "main" :type "Stage"}}
     {:relationship "contains"
      :target       {:title "default" :type "Node"}
      :source       {:title "main" :type "Class"}}]
    :name        "empty.wire-catalogs.com"
    :resources
    [{:exported   false
      :title      "Settings"
      :parameters {}
      :tags       ["class" "settings"]
      :type       "Class"}
     {:exported   false
      :title      "main"
      :parameters {:name "main"}
      :tags       ["class"]
      :type       "Class"}
     {:exported   false
      :title      "main"
      :parameters {:name "main"}
      :tags       ["stage"]
      :type       "Stage"}
     {:exported   false
      :title      "default"
      :parameters {}
      :tags       ["node" "default" "class"]
      :type       "Node"}]
    :tags             ["settings" "default" "node"]
    :classes          ["settings" "default"]
    :version          1332533763
    :transaction-uuid nil}})

(defn v1->v2-catalog
  "Converts a v1 wire catalog to v2"
  [catalog]
  (dissoc catalog :tags :classes))

(defn v2->v3-catalog
  "Converts a v2 wire catalog to v3"
  [catalog]
  (-> catalog
      v1->v2-catalog
      (assoc :transaction-uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b")))

(defn v3->v4-catalog
  "Converts a v3 wire catalog to v4"
  [catalog]
  (-> catalog
      v1->v2-catalog
      (assoc :environment "DEV")))

(def wire-catalogs
  "Catalogs keyed by version, the version 2 below is really a version 3
   catalog that happens to work for version 2, TODO on fixing this."
  {1 {:empty v1-empty-wire-catalog}

   ;; Below is really a v3 catalog that works in a v2 command
   2 {:empty
      (update-in v1-empty-wire-catalog [:data] v2->v3-catalog)
      :basic
      (update-in v1-empty-wire-catalog [:data]
                 (fn [catalog]
                   (-> catalog
                       v2->v3-catalog
                       (assoc :name "basic.wire-catalogs.com")
                       (update-in [:resources] conj {:type       "File"
                                                     :title      "/etc/foobar"
                                                     :exported   false
                                                     :file       "/tmp/foo"
                                                     :line       10
                                                     :tags       ["file" "class" "foobar"]
                                                     :parameters {:ensure "directory"
                                                                  :group  "root"
                                                                  :user   "root"}}))))}
   4 {:empty (v3->v4-catalog (:data v1-empty-wire-catalog))}})
