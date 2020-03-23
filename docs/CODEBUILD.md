# Basic AWS CodeBuild configuration



### AWS IAM service role for AWS CodeBuild

In order to use AWS CodeBuild in this test environment, you need to configure an AWS IAM role with
appropriate permissions [1].

*IMPORTANT NOTE:*

You MUST use the AWS CLI to perform the steps below, unless you can find a way
to give the IAM Principal known as `{ "Service": "codebuild.amazonaws.com" }` permission to
perform the <code>sts:AssumeRole</code> action by other means.

### Grant <code>sts:AssumeRole</code>
```shell
aws iam create-role --role-name CodeBuildServiceRole --assume-role-policy-document file://create-role.json
```
### Grant Bucket, CloudWatch Logs, CodeCommit (if required) permissions
```shell
aws iam put-role-policy --role-name CodeBuildServiceRole --policy-name CodeBuildServiceRolePolicy --policy-document file://put-role-policy.json
```

The policy documents used in the commands above can be found here:

1. [file://create-role.json](create-role.json)
1. [file://put-role-policy.json](put-role-policy.json)

### AWS IAM user for test execution

When the tests are executed in the HCS Grid, the [GridExecutionInterceptor](../src/main/java/cx/selenium/GridExecutionInterceptor.java)
will be making AWS SDK requests.  Those AWS SDK requests should be performed using an IAM user
created and configured explicitly for executing these tests. The example policy given in [1]
is a good place to start and should be attached to that IAM user.

Since AWS CodeBuild requires an IAM service role, as discussed earlier, the IAM user invoking
AWS CodeBuild needs to be able to supply that role to AWS CodeBuild.  This is why `iam:PassRole`
action shown in the policy below is allowed.

```shell
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "CodeBuildDefaultPolicy",
            "Effect": "Allow",
            "Action": [
                "codebuild:*",
                "iam:PassRole"
            ],
            "Resource": "*"
        },
        {
            "Sid": "CloudWatchLogsAccessPolicy",
            "Effect": "Allow",
            "Action": [
                "logs:FilterLogEvents",
                "logs:GetLogEvents"
            ],
            "Resource": "*"
        },
        {
            "Sid": "S3AccessPolicy",
            "Effect": "Allow",
            "Action": [
                "s3:CreateBucket",
                "s3:GetObject",
                "s3:List*",
                "s3:PutObject"
            ],
            "Resource": "*"
        },
        {
            "Sid": "S3BucketIdentity",
            "Effect": "Allow",
            "Action": [
                "s3:GetBucketAcl",
                "s3:GetBucketLocation"
            ],
            "Resource": "*"
        }
    ]
}

```

## References
1. See [AWS > Documentation > AWS CodeBuild > Advanced Setup](https://docs.aws.amazon.com/codebuild/latest/userguide/setting-up.html#setting-up-service-role)
   for more information related to IAM role configuration for AWS CodeBuild