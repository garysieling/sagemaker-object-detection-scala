import java.io.File
import java.util

import com.amazonaws.regions._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.sagemaker.AmazonSageMakerAsyncClient

object Job {
  val TRAIN_DATA_DIR = "train_data"
  val TRAIN_ANNOTATION_DIR = "train_annotation"
  val VALIDATION_DATA_DIR = "validation_data"
  val VALIDATION_ANNOTATION_DIR = "validation_annotation"
  val LST_NAME = "data.lst"

  val region = Regions.US_EAST_1

  // https://stackoverflow.com/questions/2637643/how-do-i-list-all-files-in-a-subdirectory-in-scala
  def getFileTree(f: File): Stream[File] =
    f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
    else Stream.empty)

  def main(args: Array[String]): Unit = {
    val bucket: Option[String] = None // Some("objectdetection-alerts-cats-2-2019-02-08-22-04")
    val imagePath = "/data/images/kids/photos/out/" //System.getProperty("user.dir") + "/src/main/resources/images/"
    val volumeSize = 100
    val width = 224
    val height = 224
    
    // some tags
    val tags = Map(
      "type" -> "objectdetection",
      "program" -> "kiddetector",
      "project" -> "small"
    )

    val tempPath = "/tmp/trainingJob/"
    resizeImages(imagePath, tempPath, width, height)

    uploadAndRunJob(bucket, tempPath, volumeSize, width, height, tags)
  }

  def resizeImages(src: String, dst: String, width: Int, height: Int): Unit = {
    val images = getFileTree(
      new File(src)
    ).filter(
      _.getAbsolutePath != src
    ).filter(
      !_.isDirectory
    ).map(
      (file) => (file.getAbsolutePath, dst + file.getAbsolutePath.substring(src.length))
    )

    {
      import sys.process._
      ("rm -rf " + dst).!!
    }

    images.foreach(
      (paths: (String, String)) => {
        import com.sksamuel.scrimage._
        import com.sksamuel.scrimage.nio.JpegWriter

        println(paths)

        {
          import sys.process._

          val dstFullPath = (paths._2.substring(0, paths._2.lastIndexOf("/")))
          ("mkdir -p " + dstFullPath).!!
        }

        Image.fromFile(
          new File(paths._1)
        ).scaleTo(
          width, height
        ).output(
          new File(paths._2)
        )(JpegWriter()) // specified Jpeg
      }
    )
  }

  def uploadAndRunJob(
                     bucket: Option[String],
                     imagePath: String,
                     volumeSize: Int,
                     width: Int,
                     height: Int,
                     tags: Map[String, String]
                     ) = {

    // make a bucket for a training job
    val jobId = {
      import org.joda.time.format.DateTimeFormat
      val fmt = DateTimeFormat.forPattern("yyyy-MM-dd-HH-mm")

      fmt.print(System.currentTimeMillis())
    }
    println(jobId)

    val bucketName =
      bucket match {
        case Some(x: String) => x
        case None => tags("type") + "-" + tags("program") + "-" + tags("project") + "-" + jobId
      }

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
    }

    // upload images
    def uploadDir(
                   dataDir: String,
                   annotationDir: String,
                   imagePath: String,
                   files: Iterable[File]
                 ) = {
      import java.io.File

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
        files.zipWithIndex.map(
          (fileData) => {
            val key = fileData._1.getPath.substring(imagePath.length)
            val className = key.split("/")(0)

            bucket match {
              case Some(x: String) => {

              }
              case None => {
                s3.putObject(bucketName, dataDir + "/" + key, fileData._1)
              }
            }

            val lst = fileData._2 + "\t" + (classes.indexOf(className) - 1) + "\t" + key
            lst
          }
        ).reduce(
          (a, b) => {
            a + "\n" + b
          }
        )

      val lstName = annotationDir + "/" + LST_NAME
      bucket match {
        case Some(x: String) => {

        }
        case None => {
          s3.putObject(bucketName, lstName, lst)
        }
      }

      classes
    }

    val files = {
      import scala.util.Random

      val allFiles = getFileTree(new File(imagePath)).filter(
        !_.isDirectory
      )

      val shuffled = Random.shuffle(allFiles)

      shuffled.splitAt((0.75 * allFiles.size).toInt)
    }

    val numTrainingSamples = files._1.size

    val trainingClasses =
      uploadDir(
        TRAIN_DATA_DIR,
        TRAIN_ANNOTATION_DIR,
        imagePath,
        files._1
      )

    val validationClasses =
      uploadDir(
        VALIDATION_DATA_DIR,
        VALIDATION_ANNOTATION_DIR,
        imagePath,
        files._2
      )

    val numClasses = (Set(trainingClasses) ++ Set(validationClasses)).size

    // make a training job
    {
      import com.amazonaws.services.sagemaker.model._

      val jobBuilder = AmazonSageMakerAsyncClient.asyncBuilder()
      jobBuilder.setRegion(region.getName)
      val sagemaker = jobBuilder.build()
      sagemaker.createTrainingJob({
        import scala.collection.JavaConversions._

        val request = new CreateTrainingJobRequest

        request.setTrainingJobName(
          "train-" + tags("type") + "-" + tags("program") + "-" + tags("project") + "-" + jobId
        )

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
        hp.put("image_shape", "3," + width + "," + height)
        hp.put("learning_rate", "0.1")
        hp.put("lr_scheduler_factor", "0.1")
        hp.put("mini_batch_size", "32")
        hp.put("momentum", "0.9")
        hp.put("multi_label", "0")
        hp.put("num_layers", "152")
        hp.put("num_training_samples", numTrainingSamples.toString)
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

              result.setChannelName("train")
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

              result.setChannelName("train_lst")
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
          result.setVolumeSizeInGB(volumeSize)

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
    }
  }
}
