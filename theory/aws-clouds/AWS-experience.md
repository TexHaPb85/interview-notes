# AWS Experience — Interview Answer

An answer for "tell me about your AWS experience", built from your notes, with the
inconsistencies fixed and the gaps flagged honestly instead of papered over.

## Quick links

- [Notes before you use this](#notes-before-you-use-this)
- [Short answer (30 seconds)](#short-answer-30-seconds)
- [Architecture](#architecture)
- [Deployment pipeline](#deployment-pipeline)
- [From manual CLI to CI/CD](#from-manual-cli-to-cicd)
- [Known limitations and how to talk about them](#known-limitations-and-how-to-talk-about-them)
- [Likely follow-up questions and answers](#likely-follow-up-questions-and-answers)

---

## Notes before you use this

**This section is for you, not for the interview.** Here's what I changed and why, and
what's still unconfirmed.

- **Fixed a contradiction:** you called the report-downloading service's subnet "public"
  but also said it uses a NAT Gateway and can't be reached from the internet. That
  combination doesn't exist — a NAT Gateway is what lets a **private**-subnet resource
  reach the internet outbound while staying unreachable inbound. The NAT Gateway itself
  sits in a public subnet, but the service using it does not. I corrected this to
  "private subnet" throughout.
- **Unconfirmed assumption:** your notes don't say clearly whether MSK itself was
  reachable from the internet, or just the tracking service's HTTP endpoint. I wrote this
  file assuming only the HTTP endpoint was public and MSK stayed inside the VPC — that's
  the sane, common setup. **Please confirm this.** If MSK itself was actually open to the
  internet, that's a more serious issue than fake clicks, and worth reframing honestly
  rather than leaving out.
- **The DDoS/fake-click risk** was originally a side comment in parentheses. An
  interviewer will very likely ask about it the moment you mention a public endpoint tied
  to Kafka — I moved it into its own section below with a straightforward way to bring it
  up yourself, plus concrete ideas for "what I'd do differently", since you said it was
  never addressed.
- **IAM, security groups, secrets, and console monitoring:** you said your CTO handled
  these, not you hands-on. I did not invent AWS Console experience for you — the file
  below states plainly that this was the CTO's area. If you did actually use the console
  for anything (even just checking ECS task status or reading a log while debugging),
  tell me and I'll add it truthfully.
- **Instance counts, autoscaling, and rollback strategy** weren't in your original notes.
  I left clear placeholders instead of inventing numbers — fill in the real details
  before using this in an interview.
- **The GitHub Actions YAML below is a reconstruction**, based on the steps you
  described — not a copy of your real file, since I don't have it. Adjust it to match
  what you actually built.
- **Small typo:** you wrote ".jml file" — I assume you meant ".yml" (YAML) and fixed it.
- **Practical tip:** your notes include a real-looking AWS account ID
  (`291008967373`). If you plan to share this file publicly (GitHub, a blog, etc.),
  swap it for a placeholder like `<account_id>`.

## Short answer (30 seconds)

> At my last startup-like company, I was fully responsible for two microservices
> end-to-end — from writing the code to deploying and running them on AWS. One service
> tracked clicks on links inside emails and published a Kafka event to our main backend
> for every click; it ran on ECS Fargate and used MSK for the Kafka side. The second
> service downloaded click reports from our affiliates' APIs and exposed that data only
> to our main backend, over a private network. I built the Docker images, pushed them to
> ECR, and rolled out new versions through ECS task definitions — first by running the
> AWS CLI commands manually, and later by moving the whole process into a GitHub Actions
> pipeline that runs automatically on merge requests.

## Architecture

```text
                              Internet
                                 │
                      (click on a link in an email)
                                 │
                                 ▼
        ┌───────────────────────────────────────────┐
        │  PUBLIC SUBNET                             │
        │  tracking-links-service (ECS Fargate)      │
        │  → publishes a click event to MSK          │
        └───────────────────┬─────────────────────────┘
                             │ Kafka event
                             ▼
                     ┌───────────────┐
                     │  main backend │
                     └───────┬───────┘
                             │ internal call (private network)
                             ▼
        ┌───────────────────────────────────────────┐
        │  PRIVATE SUBNET                            │
        │  report-downloading-service (ECS Fargate)  │
        │  + its own RDS PostgreSQL                  │
        │  outbound only, via NAT Gateway ───────────┼──► affiliate APIs (internet)
        └───────────────────────────────────────────┘
```

- **tracking-links-service** — public subnet, ECS Fargate. It has to be public: the
  links are inside real emails, so anyone's mail client needs to reach it directly. On
  every click it publishes an event to MSK; the main backend consumes those events.
- **report-downloading-service** — private subnet, its own RDS PostgreSQL. It calls
  affiliate APIs outbound through a NAT Gateway, but nothing from the internet can reach
  it — only the main backend can call it, over the internal network.
- Set up initially together with the CTO, who managed AWS account access; I owned the
  application code, containers, and the deployment pipeline for both services.

## Deployment pipeline

```bash
# 1. Build the jar
mvn clean package

# 2. Build the Docker image
docker build -t tracking-links-service:1.0.0 .

# 3. Tag the image for ECR — this doesn't rebuild anything, it just adds a
#    second name (pointer) to the same image. ECR needs the full registry URL
#    as a prefix, so the image is "renamed" before pushing.
docker tag tracking-links-service:1.0.0 \
  291008967373.dkr.ecr.eu-west-2.amazonaws.com/tracking-links-service:1.0.0

# 4. Log in to ECR (a temporary password, piped straight into docker login)
aws ecr get-login-password --region eu-west-2 \
  | docker login --username AWS --password-stdin 291008967373.dkr.ecr.eu-west-2.amazonaws.com

# 5. Push the image — only new/changed layers are actually uploaded,
#    Docker skips layers ECR already has.
docker push 291008967373.dkr.ecr.eu-west-2.amazonaws.com/tracking-links-service:1.0.0
```

```bash
# 6. Get the current task definition and update the image field
aws ecs describe-task-definition --task-definition my-app-backend \
  --query taskDefinition > task-def.json

jq '.containerDefinitions[0].image = "291008967373.dkr.ecr.eu-west-2.amazonaws.com/my-app:1.0.5"' \
  task-def.json > new-task-def.json

# 7. Register the new revision
aws ecs register-task-definition --cli-input-json file://new-task-def.json

# 8. Point the service at the new revision
aws ecs update-service --cluster my-app-sbox --service my-app-backend \
  --task-definition my-app-backend:<new-revision>

# 9. Wait until the service is healthy
aws ecs wait services-stable --cluster my-app-sbox --services my-app-backend
```

## From manual CLI to CI/CD

The same 9 steps, moved into a GitHub Actions job that runs automatically on a merge
request. (Reconstructed from the steps above — adjust names/secrets to match your real
workflow.)

```yaml
name: Deploy tracking-links-service

on:
  pull_request:
    types: [closed]
    branches: [main]

jobs:
  deploy:
    if: github.event.pull_request.merged == true
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build jar
        run: mvn clean package

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE }}
          aws-region: eu-west-2

      - name: Log in to ECR
        id: ecr-login
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build, tag, and push image
        run: |
          docker build -t tracking-links-service:${{ github.sha }} .
          docker tag tracking-links-service:${{ github.sha }} \
            ${{ steps.ecr-login.outputs.registry }}/tracking-links-service:${{ github.sha }}
          docker push ${{ steps.ecr-login.outputs.registry }}/tracking-links-service:${{ github.sha }}

      - name: Update ECS service
        run: |
          aws ecs describe-task-definition --task-definition my-app-backend \
            --query taskDefinition > task-def.json
          jq '.containerDefinitions[0].image = "${{ steps.ecr-login.outputs.registry }}/tracking-links-service:${{ github.sha }}"' \
            task-def.json > new-task-def.json
          aws ecs register-task-definition --cli-input-json file://new-task-def.json
          aws ecs update-service --cluster my-app-sbox --service my-app-backend \
            --task-definition my-app-backend
          aws ecs wait services-stable --cluster my-app-sbox --services my-app-backend
```

Worth saying out loud in the interview: **why** you moved to this. Manual CLI deploys
need someone with the right local AWS credentials to run the exact right commands in the
right order — easy to get wrong, hard to repeat reliably. Moving it into CI/CD made
every deploy the same, removed the "only I can deploy this" bottleneck, and gave you a
log of exactly what was deployed and when.

## Known limitations and how to talk about them

Don't wait to be asked — bringing these up yourself, with a clear "what I'd do
differently", reads as self-awareness rather than a hidden weakness.

### The public tracking endpoint had no abuse protection

Say it plainly: *"The click-tracking endpoint had to be public, since it's linked from
real emails. We didn't add rate limiting or bot protection — it was a known risk we
accepted, given the team's size and priorities at the time. If I revisited it now, I'd
add..."* — then pick from:

- **AWS WAF** with rate-based rules in front of the service (or an ALB / API Gateway)
- **CloudFront + AWS Shield Standard** — free DDoS protection layer, easy to add
- Per-IP or per-token **rate limiting** at the application level
- **Short-lived signed tokens** per email/recipient, so requests without a valid token
  get dropped immediately, before they ever reach Kafka

### Who owned security configuration

Say it plainly too: *"Our CTO owned the AWS account-level security setup — IAM roles,
security groups, secrets. I worked at the application and deployment layer: building the
services, containerizing them, and running the ECS deployment pipeline."* This is a
normal split on a small team — it only sounds like a gap if you leave it unexplained.

## Likely follow-up questions and answers

**How did you protect the tracking endpoint from bots or abuse?**
We didn't — it was a known gap, never addressed. If I did this again, I'd add rate
limiting through AWS WAF, or put CloudFront + Shield in front of it.

**Was MSK itself reachable from the internet, or just the endpoint?**
Only the service's HTTP endpoint was public. MSK stayed inside the VPC, reachable only
from services running there. *(Confirm this matches what actually happened.)*

**Who set up the IAM roles and security groups for these services?**
Our CTO managed IAM and security group configuration for the account. I worked on the
service code, the container images, and the ECS deployment pipeline.

**How were secrets — the DB password, the affiliate API keys — stored?**
That was part of the account setup our CTO managed, so I'd want to confirm the exact
mechanism before speaking to specifics. The approach I'd recommend either way is AWS
Secrets Manager or Parameter Store, so secrets never sit in plain text in a task
definition. **(Fill in the real answer here if you know it.)**

**What would you improve if you rebuilt this today?**
Rate limiting / WAF on the public endpoint, and moving the deployment fully into CI/CD
from day one instead of starting with manual CLI runs.

**How did you handle a failed deployment — was there a rollback?**
The pipeline waits for the new task definition revision to become healthy before
finishing. I didn't build an explicit automatic rollback (reverting to the previous task
definition if the new one fails) — that's something I'd add now.

**Why move from manual CLI deploys to GitHub Actions?**
To remove the "only I can deploy this" bottleneck, make every deploy repeatable in
exactly the same way, and keep a log of what was deployed and when — instead of relying
on someone running the right commands, in the right order, from their own machine.

**How many instances did each service run, and was there autoscaling?**
**(Fill in the real numbers — desired task count, min/max if autoscaling was
configured. This wasn't in your original notes, so I left it open rather than guess.)**
