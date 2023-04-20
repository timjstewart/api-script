watch:
   ls **/*.groovy | SECRET_TOKEN=apple entr -c ./hapi tests/Script1.groovy

compile:
  groovyc -d lib src/*.groovy

clean:
  rm -v lib/*.class >& /dev/null || true
