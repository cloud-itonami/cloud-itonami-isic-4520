(ns autorepair.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [autorepair.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "demo-vin-100" (:vin (store/repair-order s "ro-100"))))
      (is (= "shop-1" (:shop-id (store/repair-order s "ro-100"))))
      (is (nil? (store/repair-order s "ro-999")))
      (is (true? (:active? (store/shop-license s "shop-1"))))
      (is (false? (:active? (store/shop-license s "shop-2"))))
      (is (nil? (store/shop-license s "shop-nope")))
      (is (= [] (store/service-log s)))
      (is (= [] (store/schedule-log s)))
      (is (= [] (store/safety-flags s)))
      (is (= [] (store/parts-orders s)))
      (is (= [] (store/ledger s))))))

(deftest write-and-log-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "each op's committed record lands in its own append-only log"
        (store/commit-record! s {:op :log-service-record
                                 :value {:order-id "ro-100" :parts-used ["oil-filter"]
                                         :labor-hours 1.0 :technician "tc-1"}})
        (is (= 1 (count (store/service-log s))))
        (is (= "ro-100" (:order-id (first (store/service-log s)))))

        (store/commit-record! s {:op :schedule-service-operation
                                 :value {:order-id "ro-100" :bay "bay-1"
                                         :technician "tc-1" :start "t0" :end "t1"}})
        (is (= 1 (count (store/schedule-log s))))

        (store/commit-record! s {:op :flag-safety-concern
                                 :value {:order-id "ro-100" :concern "demo" :severity :low}})
        (is (= 1 (count (store/safety-flags s))))

        (store/commit-record! s {:op :coordinate-parts-order
                                 :value {:order-id "ro-100" :parts ["oil-filter"]
                                         :cost 40.00M :vendor "Demo Parts Co"}})
        (is (= 1 (count (store/parts-orders s)))))

      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/repair-order s "nope")))
    (is (nil? (store/shop-license s "nope")))
    (is (= [] (store/service-log s)))
    (is (= [] (store/ledger s)))
    (store/with-repair-orders s {"x" {:order-id "x" :vin "v" :shop-id "sh"
                                      :customer "c" :status :open}})
    (store/with-shop-licenses s {"sh" {:shop-id "sh" :provider "p"
                                       :jurisdiction :ca :active? true}})
    (is (= "v" (:vin (store/repair-order s "x"))))
    (is (true? (:active? (store/shop-license s "sh"))))))
