import java.util

import com.amazonaws.ClientConfiguration
import com.amazonaws.regions._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.sagemaker.AmazonSageMakerAsyncClient
import com.amazonaws.services.sagemaker.model.CreateTrainingJobRequest
import org.joda.time.DateTime

object Main {
  def dataVersion = {"0.1"}

  import com.amazonaws.services.s3.AmazonS3
  import com.amazonaws.services.s3.model.ObjectMetadata
  import com.amazonaws.services.s3.model.PutObjectRequest
  import java.io.ByteArrayInputStream
  import java.io.InputStream

  // Ultimately this will likely be unnecessary...
  def createFolder(bucketName: String, folderName: String, client: AmazonS3): Unit = { // create meta-data for your folder and set content-length to 0
    // From https://stackoverflow.com/questions/11491304/amazon-web-services-aws-s3-java-create-a-sub-directory-object
    val metadata = new ObjectMetadata
    metadata.setContentLength(0)

    val emptyContent = new ByteArrayInputStream(new Array[Byte](0))

    val putObjectRequest = new PutObjectRequest(bucketName, folderName + "/", emptyContent, metadata)

    client.putObject(putObjectRequest)
  }

  val TRAIN_DATA_DIR = "train_data"
  val TRAIN_ANNOTATION_DIR = "train_annotation"
  val VALIDATION_DATA_DIR = "validation_data"
  val VALIDATION_ANNOTATION_DIR = "validation_annotation"
  val LST_NAME = "data.lst"

  def main(args: Array[String]): Unit = {
    val region = Regions.US_EAST_1

    // some tags
    val tags = Map(
      "type" -> "objectdetection",
      "program" -> "alerts",
      "project" -> "cats",
      "classes" -> "2",
      "sha" -> dataVersion
    )

    // make a bucket for a training job
    val jobId = {
      import org.joda.time.format.DateTimeFormat
      val fmt = DateTimeFormat.forPattern("yyyy-MM-dd-HH-mm")

      fmt.print(System.currentTimeMillis())
    }
    println(jobId)

    val bucketName = tags("type") + "-" + tags("program") + "-" + tags("project") + "-" + tags("classes") + "-" + jobId

    val s3 = {
      val builder = AmazonS3Client.builder
      builder.setRegion(region.getName)

      builder
    }.build()

    {
      import com.amazonaws.services.s3.model.GetObjectRequest
      import com.amazonaws.services.s3.model.ListObjectsV2Request
      import com.amazonaws.services.s3.model.PutObjectRequest
      import com.amazonaws.services.s3.model._
      import com.amazonaws.services.s3.AmazonS3Client

      // make a bucket for the log output
      val bucket = s3.createBucket(bucketName)

      val policy =
        s"""{
           |  "Id": "Policy${jobId}",
           |  "Version": "2012-10-17",
           |  "Statement": [
           |    {
           |      "Sid": "Stmt${jobId}",
           |      "Action": [
           |        "s3:GetObject",
           |        "s3:PutObject"
           |      ],
           |      "Effect": "Allow",
           |      "Resource": "arn:aws:s3:::${bucketName}/*",
           |      "Principal": {
           |        "AWS": [
           |          "arn:aws:iam::472846177579:user/sagemaker_user"
           |        ]
           |      }
           |    }
           |  ]
           |}""".stripMargin

      println(policy)
      s3.setBucketPolicy(bucketName, policy)
      s3.setPublicAccessBlock({
        val cfg = new PublicAccessBlockConfiguration
        cfg.setBlockPublicAcls(true)
        cfg.setBlockPublicPolicy(true)
        cfg.setIgnorePublicAcls(true)
        cfg.setRestrictPublicBuckets(true)

        val req = new SetPublicAccessBlockRequest()
        req.setBucketName(bucketName)
        req.setPublicAccessBlockConfiguration(cfg)

        req
      })

      val objectTaggingRequest = new SetObjectTaggingRequest(
        bucketName,
        "",
        {
          import scala.collection.JavaConversions._

          new ObjectTagging(
            tags.map(
              (kv) => new Tag(kv._1, kv._2)
            ).toList
          )
        })
      s3.setObjectTagging(objectTaggingRequest)

      // make a folder for
      // training data
      // training logs
      // validation data
      // validation logs
      val LOGS_DIR = "logs"

      createFolder(bucketName, LOGS_DIR, s3)
      createFolder(bucketName, TRAIN_DATA_DIR, s3)
      createFolder(bucketName, TRAIN_ANNOTATION_DIR, s3)
      createFolder(bucketName, VALIDATION_DATA_DIR, s3)
      createFolder(bucketName, VALIDATION_ANNOTATION_DIR, s3)
    }

    // upload images
    val numClasses = {
      import java.io.File

      // https://stackoverflow.com/questions/2637643/how-do-i-list-all-files-in-a-subdirectory-in-scala
      def getFileTree(f: File): Stream[File] =
        f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
        else Stream.empty)

      val imagePath = System.getProperty("user.dir") + "/src/main/resources/images/"
      println(imagePath)

      // TODO multiclass
      val classes = getFileTree(
        new File(imagePath)
      ).filter(
        _.getAbsolutePath != imagePath
      ).filter(
        _.isDirectory
      ).map(
        _.getName
      ).toList

      val lst =
        getFileTree(new File(imagePath)).filter(
          !_.isDirectory
        ).zipWithIndex.map(
          (fileData) => {
            val key = fileData._1.getPath.substring(imagePath.length)
            val className = key.split("/")(0)

            s3.putObject(bucketName, TRAIN_DATA_DIR + "/" + key, fileData._1)

            val lst = fileData._2 + "\t" + (classes.indexOf(className) - 1) + "\t" + key
            lst
          }
        ).reduce(
          (a, b) => {
            a + "\n" + b
          }
        )

      val lstName = TRAIN_ANNOTATION_DIR + "/" + LST_NAME
      s3.putObject(bucketName, lstName, lst)

      classes.size
    }

    // upload configuration data for job

    // make a training job
    {
      import com.amazonaws.services.sagemaker.model._

      val jobBuilder = AmazonSageMakerAsyncClient.asyncBuilder()
      jobBuilder.setRegion(region.getName)
      val sagemaker = jobBuilder.build()
      sagemaker.createTrainingJob({
        import scala.collection.JavaConversions._

        val request = new CreateTrainingJobRequest

        request.setTrainingJobName("train-" + bucketName)

        request.setAlgorithmSpecification({
          val result = new AlgorithmSpecification()

          result.setTrainingImage("811284229777.dkr.ecr.us-east-1.amazonaws.com/image-classification:latest")
          result.setTrainingInputMode("File")

          result
        })

        val hp = new util.HashMap[String, String]()
        hp.put("num_classes", numClasses + "")

        hp.put("beta_1", "0.9")
        hp.put("beta_2", "0.999")
        hp.put("checkpoint_frequency", "1")
        hp.put("early_stopping", "false")
        hp.put("early_stopping_min_epochs", "10")
        hp.put("early_stopping_patience", "5")
        hp.put("early_stopping_tolerance", "0.0")
        hp.put("epochs", "30")
        hp.put("eps", "1e-8")
        hp.put("gamma", "0.9")
        hp.put("image_shape", "3,224,224")
        hp.put("learning_rate", "0.1")
        hp.put("lr_scheduler_factor", "0.1")
        hp.put("mini_batch_size", "32")
        hp.put("momentum", "0.9")
        hp.put("multi_label", "1")
        hp.put("num_layers", "152")
        hp.put("num_training_samples", "100")
        hp.put("optimizer", "sgd")
        hp.put("precision_dtype", "float32")
        hp.put("resize", "300")
        hp.put("use_pretrained_model", "1")
        hp.put("use_weighted_loss", "1")
        hp.put("weight_decay", "0.0001")

        request.setHyperParameters(
          hp
        )

        request.setRoleArn("arn:aws:iam::472846177579:role/service-role/AmazonSageMaker-ExecutionRole-20180912T152967")

        request.setInputDataConfig(
          List[Channel](
            {
              val result = new Channel()

              result.setChannelName("train_data")
              result.setInputMode("File")
              result.setContentType("application/x-image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/" + TRAIN_DATA_DIR)
                  s3ds.setS3DataType("S3Prefix")

                  s3ds
                })
                result
              })

              result
            },
            {
              val result = new Channel()

              result.setChannelName("train_list")
              result.setInputMode("File")
              result.setContentType("application/x-image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/" + TRAIN_ANNOTATION_DIR + "/" + LST_NAME)
                  s3ds.setS3DataType("S3Prefix")

                  s3ds
                })
                result
              })

              result
            },
            {
              val result = new Channel()

              result.setChannelName("validation")
              result.setInputMode("File")
              result.setContentType("application/x-image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/" + VALIDATION_DATA_DIR)
                  s3ds.setS3DataType("S3Prefix")

                  s3ds
                })
                result
              })

              result
            },
            {
              val result = new Channel()

              result.setChannelName("validation_lst")
              result.setInputMode("File")
              result.setContentType("application/x-image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/" + VALIDATION_ANNOTATION_DIR + "/" + LST_NAME)
                  s3ds.setS3DataType("S3Prefix")

                  s3ds
                })
                result
              })

              result
            }
          )
        )

        request.setOutputDataConfig(
          {
            val result = new OutputDataConfig

            result.setS3OutputPath("s3://" + bucketName + "/logs")
            result
          }
        )

        request.setResourceConfig({
          val result = new ResourceConfig

          result.setInstanceCount(1)
          result.setInstanceType("ml.p2.xlarge")
          result.setVolumeSizeInGB(1)

          result
        })

        request.setStoppingCondition({
          val result = new StoppingCondition

          result.setMaxRuntimeInSeconds(4 * 60 * 60)

          result
        })

        request.setTags(
          {
            import scala.collection.JavaConversions._

            val result: java.util.Collection[Tag] = tags.map(
              (kv) => {
                val t = new Tag()

                t.setKey(kv._1)
                t.setValue(kv._2)

                t
              })

            result
          }
        )

        request
      })

      //import com.amazonaws.services.sagemaker.
      /*val estimator = new KMeansSageMakerEstimator(
        sagemakerRole = IAMRole(roleArn),
        trainingInstanceType = "ml.p2.xlarge",
        trainingInstanceCount = 1,
        endpointInstanceType = "ml.c4.xlarge",
        endpointInitialInstanceCount = 1)
        .setK(10).setFeatureDim(784)*/


    }

    // run a training job



//    createBucket(bucket, region)
/*
    // Put Object
    s3.putObject(PutObjectRequest.builder.bucket(bucket).key(key).build, RequestBody.fromByteBuffer(getRandomByteBuffer(10 _000)))


    // Multipart Upload a file
    val multipartKey = "multiPartKey"
    multipartUpload(bucket, multipartKey)

    // List all objects in bucket

    // Use manual pagination
    var listObjectsReqManual = ListObjectsV2Request.builder.bucket(bucket).maxKeys(1).build

    var done = false
    while ( {
      !done
    }) {
      val listObjResponse = s3.listObjectsV2(listObjectsReqManual)
      import scala.collection.JavaConversions._
      for (content <- listObjResponse.contents) {
        System.out.println(content.key)
      }
      if (listObjResponse.nextContinuationToken == null) done = true
      listObjectsReqManual = listObjectsReqManual.toBuilder.continuationToken(listObjResponse.nextContinuationToken).build
    }

    // Build the list objects request
    val listReq = ListObjectsV2Request.builder.bucket(bucket).maxKeys(1).build

    val listRes = s3.listObjectsV2Paginator(listReq)
    // Process response pages
    listRes.stream.flatMap((r) => r.contents.stream).forEach((content) => System.out.println(" Key: " + content.key + " size = " + content.size))

    // Helper method to work with paginated collection of items directly
    listRes.contents.stream.forEach((content) => System.out.println(" Key: " + content.key + " size = " + content.size))

    // Use simple for loop if stream is not necessary
    import scala.collection.JavaConversions._
    for (content <- listRes.contents) {
      System.out.println(" Key: " + content.key + " size = " + content.size)
    }

    // Get Object
    s3.getObject(GetObjectRequest.builder.bucket(bucket).key(key).build, ResponseTransformer.toFile(Paths.get("multiPartKey")))*/
  }
}
