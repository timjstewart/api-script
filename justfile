watch:
   ls *.groovy | SECRET_TOKEN=apple entr -c groovy Script1.groovy

run:
   SECRET_TOKEN=apple groovy Main.groovy Script1.groovy

compile:
  groovyc -d lib *.groovy

clean:
  rm -v lib/*.class >& /dev/null || true