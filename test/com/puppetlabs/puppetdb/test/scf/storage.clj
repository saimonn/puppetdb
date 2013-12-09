(ns com.puppetlabs.puppetdb.test.scf.storage
  (:require [com.puppetlabs.puppetdb.catalog.utils :as catutils]
            [com.puppetlabs.random :as random]
            [com.puppetlabs.puppetdb.report.utils :as reputils]
            [com.puppetlabs.puppetdb.reports :as report-val]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [clojure.walk :as walk]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [throw+]]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [metrics.histograms :refer [sample histogram]]
            )
  (:use [com.puppetlabs.puppetdb.examples :only [catalogs]]
        [com.puppetlabs.puppetdb.examples.reports :only [reports]]
        [com.puppetlabs.puppetdb.testutils.reports]
        [com.puppetlabs.puppetdb.testutils.events]
        [com.puppetlabs.puppetdb.query.reports :only [is-latest-report?]]
        [com.puppetlabs.puppetdb.scf.storage]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]
        [clojure.test]
        [clojure.math.combinatorics :only (combinations subsets)]
        [clj-time.core :only [ago from-now now days]]
        [clj-time.coerce :only [to-timestamp to-string]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(deftest fact-persistence
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}]
      (add-certname! certname)

      (is (nil?
           (sql/transaction
            (certname-facts-metadata! "some_certname"))))
      (is (empty? (cert-fact-map "some_certname")))
      
      (add-facts! certname facts (-> 2 days ago))
      (testing "should have entries for each fact"
        (is (= (query-to-vec "SELECT certname, name, value FROM certname_facts ORDER BY name")
               [{:certname certname :name "domain" :value "mydomain.com"}
                {:certname certname :name "fqdn" :value "myhost.mydomain.com"}
                {:certname certname :name "hostname" :value "myhost"}
                {:certname certname :name "kernel" :value "Linux"}
                {:certname certname :name "operatingsystem" :value "Debian"}]))

        (is (sql/transaction
             (certname-facts-metadata! "some_certname")))
        (is (= facts (cert-fact-map "some_certname"))))
      
      (testing "should add the certname if necessary"
        (is (= (query-to-vec "SELECT name FROM certnames")
               [{:name certname}])))
      (testing "replacing facts"
        (let [deletes (atom {})
              updates (atom #{})
              adds (atom {})
              sql-insert sql/insert-records
              sql-update sql/update-values
              sql-delete sql/delete-rows]
          ;;Ensuring here that new records are inserted, updated
          ;;facts are updated (not deleted and inserted) and that
          ;;the necessary deletes happen
          (with-redefs [sql/insert-records (fn [table & rows]
                                             (swap! adds assoc table rows)
                                             (apply sql-insert table rows))
                        sql/update-values (fn [table clause values]
                                            (swap! updates conj values)
                                            (sql-update table clause values))
                        sql/delete-rows (fn [table clause]
                                          (swap! deletes assoc table clause)
                                          (sql-delete table clause))]
            (let [new-facts {"domain" "mynewdomain.com"
                             "fqdn" "myhost.mynewdomain.com"
                             "hostname" "myhost"
                             "kernel" "Linux"
                             "uptime_seconds" "3600"}]
              (replace-facts! {"name"  certname "values" new-facts} (now))
              (testing "should have only the new facts"
                (is (= (query-to-vec "SELECT name, value FROM certname_facts ORDER BY name")
                       [{:name "domain" :value "mynewdomain.com"}
                        {:name "fqdn" :value "myhost.mynewdomain.com"}
                        {:name "hostname" :value "myhost"}
                        {:name "kernel" :value "Linux"}
                        {:name "uptime_seconds" :value "3600"}])))
              (testing "should only delete operatingsystem key"
                (is (= {:certname_facts ["certname=? and name in (?)" "some_certname" "operatingsystem"]}
                       @deletes)))
              (testing "should update existing keys"
                (is (true? (contains? @updates {:value "mynewdomain.com"})))
                (is (true? (contains? @updates  {:value "myhost.mynewdomain.com"})))
                (is (some :timestamp @updates)))
              (testing "should only insert uptime_seconds"
                (is (= {:certname_facts [{:value "3600", :name "uptime_seconds", :certname "some_certname"}]}
                       @adds)))))))

      (testing "replacing all new facts"
        (delete-facts! certname)
        (replace-facts! {"name" certname
                         "values" facts} (now))
        (is (= facts (cert-fact-map "some_certname"))))

      (testing "replacing all facts with new ones"
        (delete-facts! certname)
        (add-facts! certname facts (-> 2 days ago))
        (replace-facts! {"name" certname
                         "values" {"foo" "bar"}} (now))
        (is (= {"foo" "bar"} (cert-fact-map "some_certname"))))
      
      (testing "replace-facts with only additions"
        (let [fact-map (cert-fact-map "some_certname")]
          (replace-facts! {"name" certname
                           "values" (assoc fact-map "one more" "here")} (now))
          (is (= (assoc fact-map  "one more" "here")
                 (cert-fact-map "some_certname")))))

      (testing "replace-facts with no change"
        (let [fact-map (cert-fact-map "some_certname")]
          (replace-facts! {"name" certname
                           "values" fact-map} (now))
          (is (= fact-map
                 (cert-fact-map "some_certname"))))))))

(let [catalog  (:basic catalogs)
      certname (:certname catalog)]

  (deftest catalog-persistence
    (testing "Persisted catalogs"
      (add-certname! certname)
      (add-catalog! catalog)

      (testing "should contain proper catalog metadata"
        (is (= (query-to-vec ["SELECT certname, api_version, catalog_version FROM catalogs"])
               [{:certname certname :api_version 1 :catalog_version "123456789"}])))

      (testing "should contain a complete edges list"
        (is (= (query-to-vec [(str "SELECT r1.type as stype, r1.title as stitle, r2.type as ttype, r2.title as ttitle, e.type as etype "
                                   "FROM edges e, catalog_resources r1, catalog_resources r2 "
                                   "WHERE e.source=r1.resource AND e.target=r2.resource "
                                   "ORDER BY r1.type, r1.title, r2.type, r2.title, e.type")])
               [{:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar" :etype "contains"}
                {:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "contains"}
                {:stype "File" :stitle "/etc/foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "required-by"}])))

      (testing "should contain a complete resources list"
        (is (= (query-to-vec ["SELECT type, title FROM catalog_resources ORDER BY type, title"])
               [{:type "Class" :title "foobar"}
                {:type "File" :title "/etc/foobar"}
                {:type "File" :title "/etc/foobar/baz"}]))

        (testing "properly associated with the host"
          (is (= (query-to-vec ["SELECT c.certname, cr.type, cr.title
                                 FROM catalog_resources cr, catalogs c
                                 WHERE c.id=cr.catalog_id
                                 ORDER BY cr.type, cr.title"])
                 [{:certname certname :type "Class" :title "foobar"}
                  {:certname certname :type "File"  :title "/etc/foobar"}
                  {:certname certname :type "File"  :title "/etc/foobar/baz"}])))

        (testing "with all parameters"
          (is (= (query-to-vec ["SELECT cr.type, cr.title, rp.name, rp.value FROM catalog_resources cr, resource_params rp WHERE rp.resource=cr.resource ORDER BY cr.type, cr.title, rp.name"])
                 [{:type "File" :title "/etc/foobar" :name "ensure" :value (sutils/db-serialize "directory")}
                  {:type "File" :title "/etc/foobar" :name "group" :value (sutils/db-serialize "root")}
                  {:type "File" :title "/etc/foobar" :name "user" :value (sutils/db-serialize "root")}
                  {:type "File" :title "/etc/foobar/baz" :name "ensure" :value (sutils/db-serialize "directory")}
                  {:type "File" :title "/etc/foobar/baz" :name "group" :value (sutils/db-serialize "root")}
                  {:type "File" :title "/etc/foobar/baz" :name "require" :value (sutils/db-serialize "File[/etc/foobar]")}
                  {:type "File" :title "/etc/foobar/baz" :name "user" :value (sutils/db-serialize "root")}])))

        (testing "with all metadata"
          (let [result (query-to-vec ["SELECT cr.type, cr.title, cr.exported, cr.tags, cr.file, cr.line FROM catalog_resources cr ORDER BY cr.type, cr.title"])]
            (is (= (map #(assoc % :tags (sort (:tags %))) result)
                   [{:type "Class" :title "foobar" :tags [] :exported false :file nil :line nil}
                    {:type "File" :title "/etc/foobar" :tags ["class" "file" "foobar"] :exported false :file "/tmp/foo" :line 10}
                    {:type "File" :title "/etc/foobar/baz" :tags ["class" "file" "foobar"] :exported false :file "/tmp/bar" :line 20}])))))))

  (deftest catalog-replacement
    (testing "should noop if replaced by themselves"
      (add-certname! certname)
      (let [hash (add-catalog! catalog)]
        (replace-catalog! catalog (now))

        (is (= (query-to-vec ["SELECT name FROM certnames"])
               [{:name certname}]))

        (is (= (query-to-vec ["SELECT hash FROM catalogs"])
               [{:hash hash}])))))

  (deftest edge-replacement-differential
    (testing "should do selective inserts/deletes when edges are modified just slightly"
      (add-certname! certname)
      (let [original-catalog (:basic catalogs)
            original-edges   (:edges original-catalog)
            modified-edges   (conj (disj original-edges {:source {:type "Class" :title "foobar"}
                                                         :target {:type "File" :title "/etc/foobar"}
                                                         :relationship :contains})
                                   {:source {:type "File" :title "/etc/foobar"}
                                    :target {:type "File" :title "/etc/foobar/baz"}
                                    :relationship :before})
            modified-catalog (assoc original-catalog :edges modified-edges)]
        ;; Add an initial catalog, we don't care to intercept the SQL yet
        (replace-catalog! original-catalog (now))

        (testing "ensure catalog-edges-map returns a predictable value"
          (is (= (catalog-edges-map certname)
                 {["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                   "57495b553981551c5194a21b9a26554cd93db3d9"
                   "contains"] nil,
                   ["57495b553981551c5194a21b9a26554cd93db3d9"
                    "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                    "required-by"] nil,
                   ["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                    "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                    "contains"] nil})))

        ;; Lets intercept the insert/update/delete level so we can test it later
        (let [deletes (atom {})
              adds (atom {})
              sql-insert sql/insert-rows
              sql-delete sql/delete-rows]
          (with-redefs [sql/insert-rows    (fn [table & rows]
                                             (swap! adds assoc table rows)
                                             (apply sql-insert table rows))
                        sql/delete-rows    (fn [table clause]
                                             (swap! deletes assoc table clause)
                                             (sql-delete table clause))]
            ;; Here we only replace edges, so we can capture those specific SQL
            ;; operations
            (let [resources    (:resources modified-catalog)
                  refs-to-hash (reduce-kv (fn [i k v]
                                            (assoc i k (shash/resource-identity-hash v)))
                                          {} resources)]
              (replace-edges! certname modified-edges refs-to-hash)
              (testing "ensure catalog-edges-map returns a predictable value"
                (is (= (catalog-edges-map certname)
                       {["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                         "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                         "contains"] nil,
                         ["57495b553981551c5194a21b9a26554cd93db3d9"
                          "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                          "required-by"] nil
                         ["57495b553981551c5194a21b9a26554cd93db3d9"
                          "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                          "before"] nil})))

              (testing "should only delete the 1 edge"
                (is (= {:edges ["certname=? and source=? and target=? and type=?"
                                "basic.catalogs.com"
                                "d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                                "57495b553981551c5194a21b9a26554cd93db3d9"
                                "contains"]}
                       @deletes)))
              (testing "should only insert the 1 edge"
                (is (= {:edges [["basic.catalogs.com"
                                 "57495b553981551c5194a21b9a26554cd93db3d9"
                                 "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                                 "before"]]}
                       @adds)))
              (testing "when reran to check for idempotency"
                (swap! adds {})
                (swap! deletes {})
                (replace-edges! certname modified-edges refs-to-hash)
                (testing "should delete no edges"
                  (is (= nil @deletes)))
                (testing "should insert no edges"
                  (is (= nil @adds))))))))))

  (deftest catalog-duplicates
    (testing "should share structure when duplicate catalogs are detected for the same host"
      (add-certname! certname)
      (let [hash (add-catalog! catalog)
            prev-dupe-num (.count (:duplicate-catalog metrics))
            prev-new-num  (.count (:updated-catalog metrics))]

        ;; Do an initial replacement with the same catalog
        (replace-catalog! catalog (now))
        (is (= 1 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
        (is (= 0 (- (.count (:updated-catalog metrics)) prev-new-num)))

        ;; Store a second catalog, with the same content save the version
        (replace-catalog! (assoc catalog :version "abc123") (now))
        (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
        (is (= 0 (- (.count (:updated-catalog metrics)) prev-new-num)))

        (is (= (query-to-vec ["SELECT name FROM certnames"])
               [{:name certname}]))

        (is (= (query-to-vec ["SELECT certname, hash FROM catalogs"])
               [{:hash hash
                 :certname certname}]))

        (replace-catalog! (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :line] 20) (now))
        (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
        (is (= 1 (- (.count (:updated-catalog metrics)) prev-new-num))))))

  (deftest catalog-manual-deletion
    (testing "should noop if replaced by themselves after using manual deletion"
      (add-certname! certname)
      (add-catalog! catalog)
      (delete-catalog! certname)
      (add-catalog! catalog)

      (is (= (query-to-vec ["SELECT name FROM certnames"])
             [{:name certname}]))))

  (deftest catalog-deletion-verify
    (testing "should be removed when deleted"
      (add-certname! certname)
      (let [hash (add-catalog! catalog)]
        (delete-catalog! hash))

      (is (= (query-to-vec ["SELECT * FROM catalog_resources"])
             []))))

  (deftest catalog-deletion-certnames
    (testing "when deleted, should leave certnames alone"
      (add-certname! certname)
      (add-catalog! catalog)
      (delete-catalog! certname)

      (is (= (query-to-vec ["SELECT name FROM certnames"])
             [{:name certname}]))))

  (deftest catalog-deletion-otherhosts
    (testing "when deleted, should leave other hosts' resources alone"
      (add-certname! certname)
      (add-certname! "myhost2.mydomain.com")
      (let [hash1 (add-catalog! catalog)
            ;; Store the same catalog for a different host
            hash2 (add-catalog! (assoc catalog :certname "myhost2.mydomain.com"))]
        (delete-catalog! hash1))

      ;; myhost should still be present in the database
      (is (= (query-to-vec ["SELECT name FROM certnames ORDER BY name"])
             [{:name certname} {:name "myhost2.mydomain.com"}]))

      ;; myhost1 should not have any catalogs associated with it
      ;; anymore
      (is (= (query-to-vec ["SELECT certname FROM catalogs"])
             [{:certname "myhost2.mydomain.com"}]))

      ;; All the other resources should still be there
      (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalog_resources"])
             [{:c 3}]))))

  (deftest catalog-delete-without-gc
    (testing "when deleted without GC, should leave params"
      (add-certname! certname)
      (let [hash1 (add-catalog! catalog)]
        (delete-catalog! hash1))

      ;; All the params should still be there
      (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params"])
             [{:c 7}]))
      (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params_cache"])
             [{:c 3}]))))

  (deftest catalog-delete-with-gc
    (testing "when deleted and GC'ed, should leave no dangling params or edges"
      (add-certname! certname)
      (let [hash1 (add-catalog! catalog)]
        (delete-catalog! hash1))
      (garbage-collect!)

      (is (= (query-to-vec ["SELECT * FROM resource_params"])
             []))
      (is (= (query-to-vec ["SELECT * FROM resource_params_cache"])
             [])))))

(deftest catalog-bad-input
  (testing "should noop"
    (testing "on bad input"
      (is (thrown? AssertionError (add-catalog! {})))

      ; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
            [{:nrows 0}])))))

(defn foobar->foobar2 [x]
  (if (and (string? x) (= x "/etc/foobar"))
    "/etc/foobar2"
    x))

(defn table-args
  "Many of the java.jdbc functions accept a table name as the first arg, this
   function grabs that argument"
  [coll]
  (map first coll))

(defn remove-edge-changes
  "Remove the edge related changes from the `coll` of function call arguments"
  [coll]
  (remove #(= :edges (first %)) coll))

(defn sort= [& args]
  (apply = (map sort args)))

(deftest existing-catalog-update
  (let [{:keys [certname] :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]

    (testing "inserting new catalog with resources"

      (add-certname! certname)
      (is (empty? (query-to-vec "SELECT * from catalogs where certname=?" certname)))

      (add-catalog! catalog nil old-date)

      (let [results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first results)]

        (is (= 1 (count results)))
        (is (= (to-timestamp old-date) (to-timestamp timestamp)))))
    
    (testing "changing a resource title"
      (let [{orig-id :id
             orig-tx-id :transaction_uuid
             orig-timestamp :timestamp} (first (query-to-vec "SELECT id from catalogs where certname=?" certname))
             updated-catalog (walk/prewalk foobar->foobar2 (:basic catalogs))
             new-uuid (kitchensink/uuid)
             metrics-map metrics]

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title 
                                   FROM catalogs c INNER JOIN catalog_resources cr ON c.id = cr.catalog_id
                                   WHERE c.certname=?" certname))))

        (tu/with-wrapped-fn-args [inserts sql/insert-records
                                  deletes sql/delete-rows
                                  updates sql/update-values]
          (with-redefs [metrics (assoc metrics-map :catalog-volatility (histogram [ns-str "default" (str (gensym))]))]
            (add-catalog! (assoc updated-catalog :transaction-uuid new-uuid) nil yesterday)

            ;; 2 edge deletes
            ;; 2 edge inserts
            ;; 1 params insert
            ;; 1 params cache insert
            ;; 1 catalog_resource insert
            ;; 1 catalog_resource delete
            (is (= 8.0 (apply + (sample (:catalog-volatility metrics))))))

          (is (sort= [:resource_params_cache :resource_params :catalog_resources]
                     (table-args @inserts)))
          (is (= [:catalogs]
                 (table-args @updates)))
          (is (= [[:catalog_resources ["catalog_id = ? and type = ? and title = ?" 1 "File" "/etc/foobar"]]]
                 (remove-edge-changes @deletes))))

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar2"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title 
                                   FROM catalogs c INNER JOIN catalog_resources cr ON c.id = cr.catalog_id
                                   WHERE c.certname=?" certname))))

        (let [results (query-to-vec "SELECT id, timestamp, transaction_uuid from catalogs where certname=?" certname)
              {new-timestamp :timestamp
               new-tx-id :transaction_uuid
               new-id :id} (first results)]

          (is (= 1 (count results)))
          (is (= (to-timestamp yesterday) (to-timestamp new-timestamp)))
          (is (= new-tx-id new-uuid))
          (is (= orig-id new-id))
          (is (not= orig-tx-id new-tx-id))
          (is (not= orig-timestamp new-timestamp)))))))

(deftest add-resource-to-existing-catalog
  (let [{:keys [certname] :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]
    (add-certname! certname)
    (add-catalog! catalog nil old-date)

    (is (= 3 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)))))

    (tu/with-wrapped-fn-args [inserts sql/insert-records
                           updates sql/update-values
                           deletes sql/delete-rows]
      (add-catalog! (assoc-in catalog
                              [:resources {:type "File" :title "/etc/foobar2"}]
                              {:type "File"
                               :title "/etc/foobar2"
                               :exported   false
                               :file       "/tmp/foo2"
                               :line       20
                               :tags       #{"file" "class" "foobar"}
                               :parameters {:ensure "directory"
                                            :group  "root"
                                            :user   "root"}})
                    nil old-date)

      (is (sort= [:resource_params_cache :resource_params :catalog_resources]
                 (table-args @inserts)))
      (is (= [:catalogs]
             (table-args @updates)))
      (is (empty? @deletes)))

    (is (= 4 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)))))))

(deftest removing-resources
  (let [{:keys [certname] :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))
        catalog-with-extra-resource (assoc-in catalog
                                              [:resources {:type "File" :title "/etc/the-foo"}]
                                              {:type       "File"
                                               :title      "/etc/the-foo"
                                               :exported   false
                                               :file       "/tmp/the-foo"
                                               :line       10
                                               :tags       #{"file" "class" "the-foo"}
                                               :parameters {:ensure "directory"
                                                            :group  "root"
                                                            :user   "root"}})]
    (add-certname! certname)    
    (add-catalog! catalog-with-extra-resource nil old-date)

    (let [catalog-id (:id (first (query-to-vec "SELECT id from catalogs where certname=?" certname)))]
      (is (= 4 (count (query-to-vec "SELECT * from catalog_resources where catalog_id = ?" catalog-id))))

      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]
        
        (add-catalog! catalog nil yesterday)
        (is (empty? @inserts))
        (is (= [:catalogs]
               (table-args @updates)))
        (is (= [:catalog_resources]
               (table-args @deletes))))

      (let [catalog-results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first catalog-results)
            resources (set (query-to-vec "SELECT type, title from catalog_resources where catalog_id = ?" catalog-id))]
        
        (is (= 1 (count catalog-results)))
        (is (= 3 (count resources)))
        (is (= (set (keys (:resources catalog)))
               resources))
        (is (= (to-timestamp yesterday) (to-timestamp timestamp)))))))

(defn foobar-params []
  (sql/with-query-results result-set
    ["SELECT p.name AS k, p.value AS v
      FROM catalog_resources cr, catalogs c, resource_params p
      WHERE cr.catalog_id = c.id AND cr.resource = p.resource AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
    (reduce (fn [acc row]
              (assoc acc (keyword (:k row))
                     (json/parse-string (:v row))))
            {} result-set)))

(defn foobar-params-cache []
  (sql/with-query-results result-set
    ["SELECT rpc.parameters as params
      FROM catalog_resources cr, catalogs c, resource_params_cache rpc
      WHERE cr.catalog_id = c.id AND cr.resource = rpc.resource AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
    (-> result-set
        first
        :params
        (json/parse-string true))))

(defn foobar-param-hash []
  (sql/with-query-results result-set
    ["SELECT cr.resource hash
      FROM catalog_resources cr, catalogs c
      WHERE cr.catalog_id = c.id AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
    (-> result-set
        first
        :hash)))

(deftest catalog-resource-parameter-changes
  (let [{:keys [certname] :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]
    (add-certname! certname)    
    (add-catalog! catalog nil old-date)

    (let [orig-resource-hash (foobar-param-hash)
          add-param-catalog (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters :uid] "100")]
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))
      
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))

      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]
        
        (add-catalog! add-param-catalog nil yesterday)
        (is (sort= [:catalogs :catalog_resources]
                   (table-args @updates)))
        
        (is (empty? (remove-edge-changes @deletes)))

        (is (sort= [:resource_params_cache :resource_params]
                   (table-args @inserts))))

      (is (not= orig-resource-hash (foobar-param-hash)))
      
      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))
      
      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))
      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]
        (add-catalog! catalog nil old-date)

        (is (empty? @inserts))
        (is (empty? (remove #(= :edges (first %)) @deletes)))
        (is (= (sort [:catalog_resources :catalogs])
               (sort (map first @updates)))))

      (is (= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))
      
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache))))))

(deftest catalog-referential-integrity-violation
  (testing "on input that violates referential integrity"
    ; This catalog has an edge that points to a non-existant resource
    (let [catalog (:invalid catalogs)]
      (is (thrown? AssertionError (add-catalog! {})))

      ; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
            [{:nrows 0}])))))

(deftest node-deactivation
  (let [certname        "foo.example.com"
        query-certnames #(query-to-vec ["select name, deactivated from certnames"])
        deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]
    (add-certname! certname)

    (testing "deactivating a node"
      (testing "should mark the node as deactivated"
        (deactivate-node! certname)
        (let [result (first (query-certnames))]
          (is (= certname (:name result)))
          (is (deactivated? result))))

      (testing "should not change the node if it's already inactive"
        (let [original (query-certnames)]
          (deactivate-node! certname)
          (is (= original (query-certnames))))))

    (testing "activating a node"
      (testing "should activate the node if it was inactive"
        (activate-node! certname)
        (is (= (query-certnames) [{:name certname :deactivated nil}])))

      (testing "should do nothing if the node is already active"
        (let [original (query-certnames)]
          (activate-node! certname)
          (is (= original (query-certnames))))))

    (testing "auto-reactivated based on a command"
      (let [before-deactivating (to-timestamp (ago (days 1)))
            after-deactivating  (to-timestamp (from-now (days 1)))]
        (testing "should activate the node if the command happened after it was deactivated"
          (deactivate-node! certname)
          (is (= true (maybe-activate-node! certname after-deactivating)))
          (is (= (query-certnames) [{:name certname :deactivated nil}])))

        (testing "should not activate the node if the command happened before it was deactivated"
          (deactivate-node! certname)
          (is (= false (maybe-activate-node! certname before-deactivating)))
          (let [result (first (query-certnames))]
            (is (= certname (:name result)))
            (is (deactivated? result))))

        (testing "should do nothing if the node is already active"
          (activate-node! certname)
          (is (= true (maybe-activate-node! certname (now))))
          (is (= (query-certnames) [{:name certname :deactivated nil}])))))))

(deftest node-staleness-age
  (testing "retrieving stale nodes based on age"
    (let [query-certnames #(query-to-vec ["select name, deactivated from certnames order by name"])
          deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]

      (testing "should return nothing if all nodes are more recent than max age"
        (let [catalog (:empty catalogs)
              certname (:certname catalog)]
          (add-certname! certname)
          (replace-catalog! catalog (now))
          (is (= (stale-nodes (ago (days 1))) [])))))))

(deftest node-stale-catalogs-facts
  (testing "should return nodes with a mixture of stale catalogs and facts (or neither)"
    (let [mutators [#(replace-catalog! (assoc (:empty catalogs) :certname "node1") (ago (days 2)))
                    #(replace-facts! {"name" "node1" "values" {"foo" "bar"}} (ago (days 2)))]]
      (add-certname! "node1")
      (doseq [func-set (subsets mutators)]
        (dorun (map #(%) func-set))
        (is (= (stale-nodes (ago (days 1))) ["node1"]))))))

(deftest node-max-age
  (testing "should only return nodes older than max age, and leave others alone"
    (let [catalog (:empty catalogs)]
      (add-certname! "node1")
      (add-certname! "node2")
      (replace-catalog! (assoc catalog :certname "node1") (ago (days 2)))
      (replace-catalog! (assoc catalog :certname "node2") (now))

      (is (= (set (stale-nodes (ago (days 1)))) #{"node1"})))))

(deftest node-purge
  (testing "should purge only nodes which were deactivated before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (deactivate-node! "node1")
    (with-redefs [now (constantly (ago (days 10)))]
      (deactivate-node! "node2"))

    (purge-deactivated-nodes! (ago (days 5)))

    (is (= (map :name (query-to-vec "SELECT name FROM certnames ORDER BY name ASC"))
           ["node1" "node3"]))))

;; Report tests

(let [timestamp     (now)
      report        (:basic reports)
      report-hash   (shash/report-identity-hash report)
      certname      (:certname report)]

  (deftest report-storage
    (testing "should store reports"
      (store-example-report! report timestamp)

      (is (= (query-to-vec ["SELECT certname FROM reports"])
            [{:certname (:certname report)}]))

      (is (= (query-to-vec ["SELECT hash FROM reports"])
            [{:hash report-hash}])))

    (testing "should store report with long puppet version string"
      (store-example-report!
        (assoc report
          :puppet-version "3.2.1 (Puppet Enterprise 3.0.0-preview0-168-g32c839e)") timestamp)))

  (deftest latest-report
    (testing "should flag report as 'latest'"
      (let [node        (:certname report)
            report-hash (:hash (store-example-report! report timestamp))]
        (is (is-latest-report? node report-hash))
        (let [new-report-hash (:hash (store-example-report!
                                        (-> report
                                          (assoc :configuration-version "bar")
                                          (assoc :end-time (now)))
                                        timestamp))]
          (is (is-latest-report? node new-report-hash))
          (is (not (is-latest-report? node report-hash)))))))


  (deftest report-cleanup
    (testing "should delete reports older than the specified age"
      (let [report1       (assoc report :end-time (to-string (ago (days 5))))
            report1-hash  (:hash (store-example-report! report1 timestamp))
            report2       (assoc report :end-time (to-string (ago (days 2))))
            report2-hash  (:hash (store-example-report! report2 timestamp))
            certname      (:certname report1)
            _             (delete-reports-older-than! (ago (days 3)))
            expected      (expected-reports [(assoc report2 :hash report2-hash)])
            actual        (reports-query-result ["=" "certname" certname])]
        (is (= expected actual)))))

  (deftest resource-events-cleanup
    (testing "should delete all events for reports older than the specified age"
      (let [report1       (assoc report :end-time (to-string (ago (days 5))))
            report1-hash  (:hash (store-example-report! report1 timestamp))
            report2       (assoc report :end-time (to-string (ago (days 2))))
            report2-hash  (:hash (store-example-report! report2 timestamp))
            certname      (:certname report1)
            _             (delete-reports-older-than! (ago (days 3)))
            expected      #{}
            actual        (resource-events-query-result ["=" "report" report1-hash])]
        (is (= expected actual))))))

(deftest db-deprecation?
  (testing "should return true and a string if db is deprecated"
    (let [[deprecated? message] (db-deprecated? "PostgreSQL" [8 1])]
      (is deprecated?)
      (is (string? message))))

  (testing "should return false and nil if db is not deprecated"
    (let [[deprecated? message] (db-deprecated? "PostgreSQL" [9 4])]
      (is (not deprecated?))
      (is (nil? message)))))
