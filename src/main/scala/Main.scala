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
      val bucketName = tags("type") + "-" + tags("program") + "-" + tags("project") + "-" + tags("classes") + "-" + jobId
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

        val imagePath = "/Users/gsieling/projects/gary/image/src/main/resources/images/"

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
        val request = new CreateTrainingJobRequest

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
