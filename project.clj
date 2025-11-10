(defproject opencode-clj "0.1.0-SNAPSHOT"
  :description "Clojure client library for opencode-server REST API"
  :url "https://github.com/opencode-clj/opencode-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [clj-http "3.13.1"]
                 [cheshire "6.1.0"]]
  :main ^:skip-aot opencode-clj.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:plugins [[lein-ancient "1.0.0-RC3"]
                             [lein-kibit "0.1.11"]
                             [jonase/eastwood "1.4.3"]]}})
