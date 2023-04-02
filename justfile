watch:
   ls *.groovy | SECRET_TOKEN=apple entr -c groovy Script1.groovy

run:
   SECRET_TOKEN=apple groovy Script1.groovy