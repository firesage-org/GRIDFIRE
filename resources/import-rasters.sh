# [[file:../org/GridFire.org::raster2pgsql-import-example-all][raster2pgsql-import-example-all]]
#!/bin/sh

DATABASE=gridfire
SCHEMA=landfire

for LAYER in asp cbd cbh cc ch dem fbfm13 fbfm40 slp
do
    raster2pgsql -t 100x100 -I -C $LAYER.tif $SCHEMA.$LAYER | psql -U $DATABASE &
done
# raster2pgsql-import-example-all ends here