(defproject felucca "0.1.0-SNAPSHOT"
  :description "Felucca game server"
  :url "https://github.com/mpereira/felucca-server"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 ;; Libraries.
                 [celtuce "0.3.0"]
                 [cheshire "5.9.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [info.sunng/ring-jetty9-adapter "0.12.5"]
                 [tick "0.4.23-alpha"]]
  :main ^:skip-aot felucca.core
  :repl-options {:init-ns felucca.core})
