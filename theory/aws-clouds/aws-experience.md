AWS experience:
in startup like comany I was fully responsible for development of 2 services end-to-end;
so with help of CTO who had access to our AWS we set up initial deployment to AWS;
- In our  VPC in public subnets we added 1 microservice for tracking click from emails and sending kafka events on every click to main backend;
- In same public subnets we added  1 microservice with NAT Gateway for downloading click reports from our affiliates(by their APIs) and  main BE service could call it in our priate network;
- tracking links service had MSK(managed Apache Kafka) and was public so could be reached from the internet(vulnurability that hakers could ddos it and create "fake clicks");
- report downloading service, had its own RDS postgres, could call external api in the internet but could not be reached out from the internet, could be called only by our main BE service;
  for deloying both these services I did:
 1. Build the jar
mvn clean package
 2. Build Docker image
docker build -t tracking-links-service:1.0.0 .
 3. Tag the image for ECR
    docker tag <local-name>:<tag> <new-name>:<tag>
    This does NOT copy or rebuild anything — it just adds a second name
    (pointer) to the same image. ECR requires the image name to start
    with the full registry URL, so you "rename" it before pushing.
docker tag tracking-links-service:1.0.0 291008967373.dkr.ecr.eu-west-2.amazonaws.com/tracking-links-service:1.0.0
 4. Login to ECR (get a temp password, pipe it into docker login)
aws ecr get-login-password --region eu-west-2 \
| docker login --username AWS --password-stdin 291008967373.dkr.ecr.eu-west-2.amazonaws.com
 5. Push the image
    docker push <name>:<tag>
    Uploads the image layers to the registry under that name.
    Docker checks which layers ECR already has and skips them —
    only new/changed layers actually get uploaded.
docker push 291008967373.dkr.ecr.eu-west-2.amazonaws.com/my-app:1.0.5
 6. Get current task definition, update the image field
aws ecs describe-task-definition --task-definition my-app-backend --query taskDefinition > task-def.json
jq '.containerDefinitions[0].image = "<account_id>.dkr.ecr.eu-west-2.amazonaws.com/my-app:1.0.5"' task-def.json > new-task-def.json
 7. Register new revision
aws ecs register-task-definition --cli-input-json file://new-task-def.json
 8. Point service to new revision
aws ecs update-service --cluster my-app-sbox --service my-app-backend --task-definition my-app-backend:<new-revision>
 9. Wait until healthy
aws ecs wait services-stable --cluster my-app-sbox --services my-app-backend
For some time I did these steps manually via CLI, later I describe it in separate github action .jml file to do all these steps automatically by running special job for MR.

write your remarks what is wrong here and what interview wont like, change such points