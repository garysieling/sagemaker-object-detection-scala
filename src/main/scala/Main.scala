import java.util

import com.amazonaws.ClientConfiguration
import com.amazonaws.regions._
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

    {
      import com.amazonaws.services.s3.model.GetObjectRequest
      import com.amazonaws.services.s3.model.ListObjectsV2Request
      import com.amazonaws.services.s3.model.PutObjectRequest
      import com.amazonaws.services.s3.model._
      import com.amazonaws.services.s3.AmazonS3Client

      val builder = AmazonS3Client.builder
      builder.setRegion(region.getName)

      val s3 = builder.build()

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
      createFolder(bucketName, "logs", s3)
      createFolder(bucketName, "train_data", s3)
      createFolder(bucketName, "train_annotation", s3)
      createFolder(bucketName, "validation_data", s3)
      createFolder(bucketName, "validation_annotation", s3)

      // upload images
      {
        import java.io.File

        // https://stackoverflow.com/questions/2637643/how-do-i-list-all-files-in-a-subdirectory-in-scala
        def getFileTree(f: File): Stream[File] =
          f #:: (if (f.isDirectory) f.listFiles().toStream.flatMap(getFileTree)
          else Stream.empty)

        val imagePath = System.getProperty("user.dir") + "/src/main/resources/images/"
        println(imagePath)

        getFileTree(new File(imagePath)).filter(
          !_.isDirectory
        ).map(
          file => {
            val key = file.getPath.substring(imagePath.length)
            print(key)
            s3.putObject(bucketName, "train_data/" + key, file)
          }
        ).foreach(println)
      }
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
        hp.put("beta_1", "09.9")
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
              result.setContentType("application/image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/train")
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
              result.setContentType("application/image")
              result.setDataSource({
                val result = new DataSource()

                result.setS3DataSource({
                  val s3ds = new S3DataSource

                  s3ds.setS3Uri("s3://" + bucketName + "/train")
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
        /*
        AlgorithmSpecification - Identifies the training algorithm to use.

HyperParameters - Specify these algorithm-specific parameters to influence the quality of the final model. For a list of hyperparameters for each training algorithm provided by Amazon SageMaker, see Algorithms.

InputDataConfig - Describes the training dataset and the Amazon S3 location where it is stored.

OutputDataConfig - Identifies the Amazon S3 location where you want Amazon SageMaker to save the results of model training.

ResourceConfig - Identifies the resources, ML compute instances, and ML storage volumes to deploy for model training. In distributed training, you specify more than one instance.

RoleARN - The Amazon Resource Number (ARN) that Amazon SageMaker assumes to perform tasks on your behalf during model training. You must grant this role the necessary permissions so that Amazon SageMaker can successfully complete model training.

StoppingCondition - Sets a duration for training. Use this parameter to cap model training costs.
         */
//        request.setAlgorithmSpecification()

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

        /*
        Job name
object-detection-2019-01-30-21-02-07
ARN
arn:aws:sagemaker:us-east-1:472846177579:training-job/object-detection-2019-01-30-21-02-07
Status
Failed
View history
Creation time
Jan 31, 2019 02:02 UTC
Last modified time
Jan 31, 2019 02:05 UTC
Training duration
a minute
Tuning job source/parent
-
IAM role ARN
arn:aws:iam::472846177579:role/service-role/AmazonSageMaker-ExecutionRole-20180912T152967
Algorithm
Algorithm ARN
-
Training image
811284229777.dkr.ecr.us-east-1.amazonaws.com/object-detection:latest
Input mode
File
Instance type
ml.p2.xlarge
Instance count
1
Additional volume size (GB)
5
Maximum runtime (s)
54000
Volume encryption key
-
Input data configuration: train_annotation
Channel name
train_annotation
Input mode
File
Content type
application/x-image
S3 data type
S3Prefix
Compression type
None
URI
s3://sieling.household/train
Record wrapper type
None
S3 data distribution type
FullyReplicated
Input data configuration: train
Channel name
train
Input mode
File
Content type
application/x-image
S3 data type
S3Prefix
Compression type
None
URI
s3://sieling.household/train
Record wrapper type
None
S3 data distribution type
FullyReplicated
Input data configuration: validation
Channel name
validation
Input mode
File
Content type
application/x-image
S3 data type
S3Prefix
Compression type
None
URI
s3://sieling.household/validation
Record wrapper type
None
S3 data distribution type
FullyReplicated
Input data configuration: validation_annotation
Channel name
validation_annotation
Input mode
File
Content type
application/x-image
S3 data type
S3Prefix
Compression type
None
URI
s3://sieling.household/validation_annotation
Record wrapper type
None
S3 data distribution type
FullyReplicated
Metrics
Name	Regex
train:progress	#progress_metric: host=\S+, completed (\S+) %
train:smooth_l1	#quality_metric: host=\S+, epoch=\S+, batch=\S+ train smooth_l1 <loss>=(\S+)
train:cross_entropy	#quality_metric: host=\S+, epoch=\S+, batch=\S+ train cross_entropy <loss>=(\S+)
train:throughput	#throughput_metric: host=\S+, train throughput=(\S+) records/second
validation:mAP	#quality_metric: host=\S+, epoch=\S+, validation mAP <score>=\((\S+)\)
Output data configuration
S3 output path
s3://sieling.household/logs
Output encryption key
-
Hyperparameters
Key	Value
base_network	vgg-16
early_stopping	false
early_stopping_min_epochs	10
early_stopping_patience	5
early_stopping_tolerance	0.0
epochs	200
freeze_layer_pattern	false
image_shape	300
label_width	350
learning_rate	0.001
lr_scheduler_factor	0.1
mini_batch_size	32
momentum	0.9
nms_threshold	0.45
num_classes	4
num_training_samples	300
optimizer	sgd
overlap_threshold	0.5
use_pretrained_model	1
weight_decay	0.0005
Network
No custom VPC settings applied.
Monitor
Access logs for debugging and progress reporting. View metrics to set alarms, send notifications, or take actions.Learn more
View algorithm metrics
View logs
View instance metrics

Output
S3 model artifact
s3://sieling.household/logs/object-detection-2019-01-30-21-02-07/output/model.tar.gz */

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
