watch:
   ls *.groovy | SECRET_TOKEN=apple entr -c groovy Main.groovy Script1.groovy

run:
   SECRET_TOKEN=apple groovy Main.groovy Script1.groovy