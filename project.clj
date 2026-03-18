(defproject anima-agent-clj "0.1.0-SNAPSHOT"
  :description "Clojure multi-agent SDK with message bus and dispatcher architecture"
  :url "https://github.com/anima-agent-clj/anima-agent-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/core.async "1.9.829-alpha2"]
                 [org.clojure/data.json "2.5.2"]

                 [clj-http "3.13.1"]
                 [cheshire "6.1.0"]

                 ;; RabbitMQ support
                 [com.novemberain/langohr "5.6.0"]]
  :main ^:skip-aot anima-agent-clj.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:source-paths ["examples"]
                   :plugins [[lein-ancient "1.0.0-RC3"]
                             [lein-kibit "0.1.11"]
                             [jonase/eastwood "1.4.3"]]}}
  :aliases {"cli" ["run" "-m" "anima-agent-clj.cli-main"]})
