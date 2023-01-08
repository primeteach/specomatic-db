#!/bin/bash

cd $(dirname "$0")
clojure -Mtest:runner --fail-fast
