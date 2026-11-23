#!/bin/bash
# LocalStack "ready" hook (mounted into /etc/localstack/init/ready.d/ by docker-compose.yml).
#
# Creates what Terraform/CDK would create in AWS:
#   SNS topic  --(subscription, raw delivery)-->  SQS order queue  --(RedrivePolicy)-->  SQS dead-letter queue
#
# Names and the redrive threshold come from the environment, which docker compose fills from .env; the defaults
# below match .env.example. The application only looks these resources up, it never creates them.
set -euo pipefail

TOPIC_NAME="${SNS_ORDER_TOPIC_NAME:-order-events}"
QUEUE_NAME="${SQS_ORDER_QUEUE_NAME:-order-processing}"
DLQ_NAME="${SQS_ORDER_DLQ_NAME:-order-processing-dlq}"
MAX_RECEIVE_COUNT="${MESSAGING_MAX_RECEIVE_COUNT:-3}"
VISIBILITY_TIMEOUT="${SQS_VISIBILITY_TIMEOUT_SECONDS:-30}"

work_dir=$(mktemp -d)
trap 'rm -rf "${work_dir}"' EXIT

echo "Creating dead-letter queue ${DLQ_NAME}"
DLQ_URL=$(awslocal sqs create-queue --queue-name "${DLQ_NAME}" \
  --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "${DLQ_URL}" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

echo "Creating queue ${QUEUE_NAME}: dead-letters to ${DLQ_NAME} after ${MAX_RECEIVE_COUNT} receives"
cat > "${work_dir}/queue-attributes.json" <<EOF
{
  "VisibilityTimeout": "${VISIBILITY_TIMEOUT}",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"${MAX_RECEIVE_COUNT}\"}"
}
EOF
QUEUE_URL=$(awslocal sqs create-queue --queue-name "${QUEUE_NAME}" \
  --attributes "file://${work_dir}/queue-attributes.json" --query QueueUrl --output text)
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "${QUEUE_URL}" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

echo "Creating topic ${TOPIC_NAME}"
TOPIC_ARN=$(awslocal sns create-topic --name "${TOPIC_NAME}" --query TopicArn --output text)

# Let the topic (and only it) deliver to the queue. AWS enforces this policy; LocalStack accepts it as-is.
cat > "${work_dir}/queue-policy.json" <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"${QUEUE_ARN}\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"${TOPIC_ARN}\"}}}]}"
}
EOF
awslocal sqs set-queue-attributes --queue-url "${QUEUE_URL}" --attributes "file://${work_dir}/queue-policy.json"

echo "Subscribing ${QUEUE_NAME} to ${TOPIC_NAME}"
awslocal sns subscribe --topic-arn "${TOPIC_ARN}" --protocol sqs --notification-endpoint "${QUEUE_ARN}" \
  --attributes RawMessageDelivery=true --output text > /dev/null

echo "SNS/SQS ready: ${TOPIC_ARN} -> ${QUEUE_ARN} (DLQ ${DLQ_ARN}, maxReceiveCount ${MAX_RECEIVE_COUNT})"
