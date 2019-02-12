docker run -p 7077:7077 \
  -p 9080:8080 \
  --privileged=true \
  -v $PWD/logs:/logs \
  -v $PWD/notebook:/notebook \
  -v /data:/data \
  -v $PWD/conf:/zeppelin/conf/ \
  -it \
  -e ZEPPELIN_NOTEBOOK_DIR='/notebook' \
  -e ZEPPELIN_LOG_DIR='/logs' \
  apache/zeppelin:0.8.1

