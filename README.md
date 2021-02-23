# 영화 예매

MSA/DDD/Event Storming/EDA 를 포괄하는 분석/설계/구현/운영 전단계를 커버하도록 구성한 3조 프로젝트 과제입니다.

- 체크포인트 : https://workflowy.com/s/assessment-check-po/T5YrzcMewfo4J6LW

# Table of contents

- [과제 - 영화예매](#---)
  - [서비스 시나리오](#서비스-시나리오)
  - [체크포인트](#체크포인트)
  - [분석/설계](#분석설계)
  - [구현:](#구현-)
    - [DDD 의 적용](#ddd-의-적용)
    - [폴리글랏 퍼시스턴스](#폴리글랏-퍼시스턴스)
    - [폴리글랏 프로그래밍](#폴리글랏-프로그래밍)
    - [동기식 호출 과 Fallback 처리](#동기식-호출-과-Fallback-처리)
    - [비동기식 호출 과 Eventual Consistency](#비동기식-호출-과-Eventual-Consistency)
  - [운영](#운영)
    - [CI/CD 설정](#cicd설정)
    - [동기식 호출 / 서킷 브레이킹 / 장애격리](#동기식-호출-서킷-브레이킹-장애격리)
    - [오토스케일 아웃](#오토스케일-아웃)
    - [무정지 재배포](#무정지-재배포)
  - [신규 개발 조직의 추가](#신규-개발-조직의-추가)

# 서비스 시나리오

기능적 요구사항

1. 고객이 영화 및 좌석을 선택하고 예매를 한다.
1. 고객이 결제를 진행한다.
1. 예매 및 결재가 완료되면 my page에 전달된다.(status: 예매완료(1))
1. 영화관에서 나의 예매 정보로 티켓을 수령한다.(status: 티켓수령(2))
1. 티켓 수령 전까지 고객이 예매를 취소할 수 있다.
1. 예매가 취소되면 결재가 취소된다.(status : 예매취소(9))
1. 고객이 예매 내역 및 상태를 조회할 수 있다.

비기능적 요구사항

1. 트랜잭션
   1. 결제가 되지 않은 예매 건은 아예 예매가 성립되지 않아야 한다. Sync 호출
1. 장애격리
   1. 티켓 수령 기능이 수행되지 않더라도 예매는 365일 24시간 받을 수 있어야 한다. Async (event-driven), Eventual Consistency
   1. 결제시스템이 과중되면 사용자를 잠시동안 받지 않고 결제를 잠시후에 하도록 유도한다. Circuit breaker, fallback
1. 성능
   1. 고객이 예매 내역을 my page(프론트엔드)에서 확인할 수 있어야 한다 CQRS
   1. 예매 상태가 바뀔때마다 카톡 등으로 알림을 줄 수 있어야 한다 Event driven

# 분석/설계

## Event Storming 결과

- MSAEz 로 모델링한 이벤트스토밍 결과: http://www.msaez.io/#/storming/R6mhRNYqDQNZGOm0lF9mkOuyQb22/mine/71ff9c1518aee16ab14394848c5ab5f8
  <img width="733" alt="스크린샷 2021-02-19 오후 4 18 04" src="https://user-images.githubusercontent.com/28583602/108471436-be82ba80-72ce-11eb-9615-f5b48346e771.png">

## 헥사고날 아키텍처 다이어그램 도출

![image](https://user-images.githubusercontent.com/74696451/108643586-7c37c400-74ee-11eb-88c1-3275a73f08e6.png)

# 구현:

분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 각 BC별로 대변되는 마이크로 서비스들을 스프링부트로 구현하였다. 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 808n 이다)

```
cd book
mvn spring-boot:run

cd payment
mvn spring-boot:run

cd ticket
mvn spring-boot:run

cd mypage
mvn srping-boot:run
```

## 동기식 호출

분석단계에서의 조건 중 하나로 예매(book)->결제(pay) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다.
호출 프로토콜은 이미 앞서 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

- 결제서비스를 호출하기 위하여 Stub과 (FeignClient) 를 이용하여 Service 대행 인터페이스 (Proxy) 를 구현

```
# PaymentService.java

package movie.external;

@FeignClient(name="payment", url="http://localhost:8082")
public interface PaymentService {

    @RequestMapping(method= RequestMethod.POST, path="/payments")
    public void pay(@RequestBody Payment payment);

    @RequestMapping(method= RequestMethod.POST, path="/cancellations")
    public void cancel(@RequestBody Payment payment);

}
```

- 예매 직후(@PostPersist) 결제를 요청하도록 처리

```
# Book.java (Entity)

    @PostPersist
    public void onPostPersist(){

        Booked booked = new Booked();
        BeanUtils.copyProperties(this, booked);
        booked.publishAfterCommit();
        movie.external.Payment payment = new movie.external.Payment();

        System.out.println("*********************");
        System.out.println("결제 이벤트 발생");
        System.out.println("*********************");

        // mappings goes here
        payment.setBookingId(booked.getId());
        payment.setStatus("Paid");
        payment.setTotalPrice(booked.getTotalPrice());
        BookApplication.applicationContext.getBean(movie.external.PaymentService.class)
            .pay(payment);
    }
```

- 동기식 호출에서는 호출 시간에 따른 타임 커플링이 발생하며, 결제 시스템이 장애가 나면 주문도 못받는다는 것을 확인


- 결제 (pay) 서비스를 잠시 내려놓음 (ctrl+c)

1. 주문처리

<img width="688" alt="스크린샷 2021-02-23 오전 11 16 37" src="https://user-images.githubusercontent.com/28583602/108794189-ab226880-75c8-11eb-8692-cb06effe8bb2.png">


2. 결제서비스 재기동
```
cd ../payment
mvn spring-boot:run
```

3. 주문처리

<img width="692" alt="스크린샷 2021-02-23 오전 11 18 23" src="https://user-images.githubusercontent.com/28583602/108794296-da38da00-75c8-11eb-8d86-fce182516fa7.png">


## 비동기식 호출

결제가 이루어진 후에 Ticket시스템으로 이를 알려주는 행위는 동기식이 아니라 비 동기식으로 처리한다.

- 이를 위하여 결제이력에 기록을 남긴 후에 곧바로 예매  되었다는 도메인 이벤트를 카프카로 송출한다(Publish)

```
package movie;

@Entity
@Table(name="Book_table")
public class Payment {

 ...
    @PrePersist
    public void onPrePersist(){
        Booked booked = new Booked();
        BeanUtils.copyProperties(this, booked);
        booked.publishAfterCommit();
    }

}
```

- Ticket 서비스에서는 Booked 이벤트에 대해서 이를 수신하여 자신의 정책을 처리하도록 PolicyHandler 를 구현한다:

```
package movie;

...

@Service
public class PolicyHandler{

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverBooked_(@Payload Booked booked){

        if(booked.isMe()){
            System.out.println("======================================");
            System.out.println("##### listener  : " + booked.toJson());
            System.out.println("======================================");
            
            Ticket ticket = new Ticket();
            ticket.setBookingId(booked.getId());
            ticket.setMovieName(booked.getMovieName());
            ticket.setQty(booked.getQty());
            ticket.setSeat(booked.getSeat());
            ticket.setStatus("Waiting");

            ticketRepository.save(ticket);
        }
    }

}

```
- Ticket 시스템은 주문/결제와 완전히 분리되어있으며, 이벤트 수신에 따라 처리되기 때문에, Ticket 시스템이 유지보수로 인해 잠시 내려간 상태라도 예매 받는데 문제가 없다:

- 상점 서비스 (store) 를 잠시 내려놓음 (ctrl+c)

1. 주문처리
<img width="1056" alt="스크린샷 2021-02-23 오후 1 12 47" src="https://user-images.githubusercontent.com/28583602/108801338-d3b25e80-75d8-11eb-9a01-094c0c926c03.png">
<img width="1441" alt="스크린샷 2021-02-23 오후 1 13 01" src="https://user-images.githubusercontent.com/28583602/108801356-dca33000-75d8-11eb-8a05-fd69895406f4.png">


2. 주문상태 확인
<img width="859" alt="스크린샷 2021-02-23 오후 1 15 10" src="https://user-images.githubusercontent.com/28583602/108801469-2a1f9d00-75d9-11eb-8a08-b0a3a64df1ab.png">

3. Ticket 서비스 기동
```
cd ../ticket
mvn spring-boot:run
```

4. 주문상태 확인
<img width="882" alt="스크린샷 2021-02-23 오후 1 19 34" src="https://user-images.githubusercontent.com/28583602/108801714-c8136780-75d9-11eb-8a24-1022857d70e4.png">


## GateWay

- Gateway의 application.yaml에 모든 서비스들이 8088 포트를 사용할 수 있도록 한다.


```
# gateway.application.yaml
spring:
  profiles: default
  cloud:
    gateway:
      routes:
        - id: book
          uri: http://localhost:8081
          predicates:
            - Path=/books/** 
        - id: payment
          uri: http://localhost:8082
          predicates:
            - Path=/payments/** 
        - id: mypage
          uri: http://localhost:8083
          predicates:
            - Path= /mypages/**
        - id: ticket
          uri: http://localhost:8084
          predicates:
            - Path=/tickets/** 
          
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "*"
            allowedMethods:
              - "*"
            allowedHeaders:
              - "*"
            allowCredentials: true

```

- 8088 포트를 사용하여 API를 발생시킨다..

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# 주문 상태 확인
http http://localhost:8088/books/1

```
<img width="1180" alt="스크린샷 2021-02-23 오후 1 32 28" src="https://user-images.githubusercontent.com/28583602/108802418-94394180-75db-11eb-93ab-c05554651c89.png">

## Mypage

- 고객은 예매 상태를 Mypage에서 확인할 수 있다.

- REST API 의 테스트

```
# book 서비스의 예매처리
http POST http://localhost:8088/books qty=2 movieName="soul" seat="1A,2B" totalPrice=10000

# ticket 서비스의 출력처리
http PATCH http://localhost:8088/tickets/1 status="Printed"

# 주문 상태 확인
http http://localhost:8088/books/1

```

<img width="885" alt="스크린샷 2021-02-23 오후 1 33 46" src="https://user-images.githubusercontent.com/28583602/108802487-c34fb300-75db-11eb-8be8-1ff696dd8563.png">
<img width="1099" alt="스크린샷 2021-02-23 오후 1 34 36" src="https://user-images.githubusercontent.com/28583602/108802521-dfebeb00-75db-11eb-9f41-6382e7b5feee.png">

## 폴리글랏 퍼시스턴스

```
# Book - pom.xml

		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>runtime</scope>
		</dependency>


# Ticket - pom.xml

		<dependency>
			<groupId>org.hsqldb</groupId>
			<artifactId>hsqldb</artifactId>
			<scope>runtime</scope>
		</dependency>

```



# 운영

## CI/CD 설정 / Pipeline

각 구현체들은 Amazon ECR(Elastic Container Registry)에 구성되었고, 사용한 CI/CD 플랫폼은 AWS Codebuild며, pipeline build script 는 각 프로젝트 폴더 이하에 buildspec.yml 에 포함되었다. 

```
# ticket/buildspec.yaml
version: 0.2

env:
  variables:
    _PROJECT_NAME: "ticket"
    _PROJECT_DIR: "ticket"
    CODEBUILD_RESOLVED_SOURCE_VERSION: "v3"

phases:
  install:
    runtime-versions:
      java: openjdk8
      docker: 18
    commands:
      - echo install kubectl
      # - curl -LO https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl
      # - chmod +x ./kubectl
      # - mv ./kubectl /usr/local/bin/kubectl
  pre_build:
    commands:
      - echo Logging in to Amazon ECR...
      - echo $_PROJECT_NAME
      - echo $AWS_ACCOUNT_ID
      - echo $AWS_DEFAULT_REGION
      - echo $CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo start command
      - $(aws ecr get-login --no-include-email --region $AWS_DEFAULT_REGION)
  build:
    commands:
      - echo Build started on `date`
      - echo Building the Docker image...
      - cd $_PROJECT_DIR
      - mvn package -Dmaven.test.skip=true
      - docker build -t $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION  .
  post_build:
    commands:
      - echo Pushing the Docker image...
      - docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
      - echo connect kubectl
      - kubectl config set-cluster k8s --server="$KUBE_URL" --insecure-skip-tls-verify=true
      - kubectl config set-credentials admin --token="$KUBE_TOKEN"
      - kubectl config set-context default --cluster=k8s --user=admin
      - kubectl config use-context default
      - |
        cat <<EOF | kubectl apply -f -
        apiVersion: v1
        kind: Service
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          ports:
            - port: 8080
              targetPort: 8080
          selector:
            app: $_PROJECT_NAME
        EOF
      - |
        cat  <<EOF | kubectl apply -f -
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: $_PROJECT_NAME
          namespace: movie
          labels:
            app: $_PROJECT_NAME
        spec:
          replicas: 1
          selector:
            matchLabels:
              app: $_PROJECT_NAME
          template:
            metadata:
              labels:
                app: $_PROJECT_NAME
            spec:
              containers:
                - name: $_PROJECT_NAME
                  image: $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/skteam03-$_PROJECT_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
                  ports:
                    - containerPort: 8080
                  readinessProbe:
                    tcpSocket:
                      port: 8080
                    initialDelaySeconds: 10
                    timeoutSeconds: 2
                    periodSeconds: 5
                    failureThreshold: 10
                  livenessProbe:
                    httpGet:
                      path: /abc
                      port: 8080
                    initialDelaySeconds: 120
                    timeoutSeconds: 2
                    periodSeconds: 5
                    failureThreshold: 5
        EOF
cache:
  paths:
    - "/root/.m2/**/*"
```

- 서비스 이미지
<img width="1655" alt="aws_ecr_team" src="https://user-images.githubusercontent.com/60732832/108799930-0ce8cf80-75d5-11eb-97e9-3e47f8a73595.png">

- Pipeline

![aws_team_codebuild](https://user-images.githubusercontent.com/60732832/108794185-a958a500-75c8-11eb-9a99-8d6129053774.png)

## 동기식 호출 / 서킷 브레이킹 / 장애격리

- 서킷 브레이킹 프레임워크의 선택: Spring FeignClient + Hystrix 옵션을 사용하여 구현함

시나리오는 단말앱(app)-->결제(pay) 시의 연결을 RESTful Request/Response 로 연동하여 구현이 되어있고, 결제 요청이 과도할 경우 CB 를 통하여 장애격리.

- Hystrix 를 설정: 요청처리 쓰레드에서 처리시간이 610 밀리가 넘어서기 시작하여 어느정도 유지되면 CB 회로가 닫히도록 (요청을 빠르게 실패처리, 차단) 설정

```
# application.yml

hystrix:
  command:
    # 전역설정
    default:
      execution.isolation.thread.timeoutInMilliseconds: 610

```

- 피호출 서비스(결제:pay) 의 임의 부하 처리 - 400 밀리에서 증감 220 밀리 정도 왔다갔다 하게

```
# (pay) 결제이력.java (Entity)

    @PrePersist
    public void onPrePersist(){  //결제이력을 저장한 후 적당한 시간 끌기

        ...

        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
```

- 부하테스터 siege 툴을 통한 서킷 브레이커 동작 확인:

* 동시사용자 100명
* 60초 동안 실시

```
$ siege -c100 -t60S -r10 --content-type "application/json" 'http://localhost:8081/orders POST {"item": "chicken"}'

** SIEGE 4.0.5
** Preparing 100 concurrent users for battle.
The server is now under siege...

HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.73 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.75 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.77 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.97 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.81 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.87 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.12 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.16 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.17 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.26 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.25 secs:     207 bytes ==> POST http://localhost:8081/orders

* 요청이 과도하여 CB를 동작함 요청을 차단

HTTP/1.1 500     1.29 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.24 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.23 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.42 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     2.08 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.29 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.24 secs:     248 bytes ==> POST http://localhost:8081/orders

* 요청을 어느정도 돌려보내고나니, 기존에 밀린 일들이 처리되었고, 회로를 닫아 요청을 다시 받기 시작

HTTP/1.1 201     1.46 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.33 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.36 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.63 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.65 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.69 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.71 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.71 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.74 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.76 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     1.79 secs:     207 bytes ==> POST http://localhost:8081/orders

* 다시 요청이 쌓이기 시작하여 건당 처리시간이 610 밀리를 살짝 넘기기 시작 => 회로 열기 => 요청 실패처리

HTTP/1.1 500     1.93 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.92 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     1.93 secs:     248 bytes ==> POST http://localhost:8081/orders

* 생각보다 빨리 상태 호전됨 - (건당 (쓰레드당) 처리시간이 610 밀리 미만으로 회복) => 요청 수락

HTTP/1.1 201     2.24 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.32 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.16 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.19 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.19 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.19 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.21 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.29 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.30 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.38 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.59 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.61 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.62 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     2.64 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.01 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.27 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.33 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.45 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.52 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.57 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.69 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.70 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.69 secs:     207 bytes ==> POST http://localhost:8081/orders

* 이후 이러한 패턴이 계속 반복되면서 시스템은 도미노 현상이나 자원 소모의 폭주 없이 잘 운영됨


HTTP/1.1 500     4.76 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.23 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.76 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.74 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.82 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.82 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.84 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.66 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     5.03 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.22 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.19 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.18 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.69 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.65 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     5.13 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.84 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.25 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.25 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.80 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.87 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.33 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.86 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.96 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.34 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 500     4.04 secs:     248 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.50 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.95 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.54 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     4.65 secs:     207 bytes ==> POST http://localhost:8081/orders


:
:

Transactions:		        1025 hits
Availability:		       63.55 %
Elapsed time:		       59.78 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02
Successful transactions:        1025
Failed transactions:	         588
Longest transaction:	        9.20
Shortest transaction:	        0.00

```

- 운영시스템은 죽지 않고 지속적으로 CB 에 의하여 적절히 회로가 열림과 닫힘이 벌어지면서 자원을 보호하고 있음을 보여줌. 하지만, 63.55% 가 성공하였고, 46%가 실패했다는 것은 고객 사용성에 있어 좋지 않기 때문에 Retry 설정과 동적 Scale out (replica의 자동적 추가,HPA) 을 통하여 시스템을 확장 해주는 후속처리가 필요.

- Retry 의 설정 (istio)
- Availability 가 높아진 것을 확인 (siege)

### 오토스케일 아웃

앞서 CB 는 시스템을 안정되게 운영할 수 있게 해줬지만 사용자의 요청을 100% 받아들여주지 못했기 때문에 이에 대한 보완책으로 자동화된 확장 기능을 적용하고자 한다.

- 결제서비스에 대한 replica 를 동적으로 늘려주도록 HPA 를 설정한다. 설정은 CPU 사용량이 15프로를 넘어서면 replica 를 10개까지 늘려준다:

```
kubectl autoscale deploy pay --min=1 --max=10 --cpu-percent=15
```

- CB 에서 했던 방식대로 워크로드를 2분 동안 걸어준다.

```
siege -c100 -t120S -r10 --content-type "application/json" 'http://localhost:8081/orders POST {"item": "chicken"}'
```

- 오토스케일이 어떻게 되고 있는지 모니터링을 걸어둔다:

```
kubectl get deploy pay -w
```

- 어느정도 시간이 흐른 후 (약 30초) 스케일 아웃이 벌어지는 것을 확인할 수 있다:

```
NAME    DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
pay     1         1         1            1           17s
pay     1         2         1            1           45s
pay     1         4         1            1           1m
:
```

- siege 의 로그를 보아도 전체적인 성공률이 높아진 것을 확인 할 수 있다.

```
Transactions:		        5078 hits
Availability:		       92.45 %
Elapsed time:		       120 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02
```

## 무정지 재배포

- 먼저 무정지 재배포가 100% 되는 것인지 확인하기 위해서 Autoscaler 이나 CB 설정을 제거함

* seige 로 배포작업 직전에 워크로드를 모니터링 함.

```
siege -c100 -t120S -r10 --content-type "application/json" 'http://localhost:8081/orders POST {"item": "chicken"}'

** SIEGE 4.0.5
** Preparing 100 concurrent users for battle.
The server is now under siege...

HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.68 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
HTTP/1.1 201     0.70 secs:     207 bytes ==> POST http://localhost:8081/orders
:

```

- 새버전으로의 배포 시작

```
kubectl set image ...
```

- seige 의 화면으로 넘어가서 Availability 가 100% 미만으로 떨어졌는지 확인

```
Transactions:		        3078 hits
Availability:		       70.45 %
Elapsed time:		       120 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02

```

배포기간중 Availability 가 평소 100%에서 70% 대로 떨어지는 것을 확인. 원인은 쿠버네티스가 성급하게 새로 올려진 서비스를 READY 상태로 인식하여 서비스 유입을 진행한 것이기 때문. 이를 막기위해 Readiness Probe 를 설정함:

```
# deployment.yaml 의 readiness probe 의 설정:


kubectl apply -f kubernetes/deployment.yaml
```

- 동일한 시나리오로 재배포 한 후 Availability 확인:

```
Transactions:		        3078 hits
Availability:		       100 %
Elapsed time:		       120 secs
Data transferred:	        0.34 MB
Response time:		        5.60 secs
Transaction rate:	       17.15 trans/sec
Throughput:		        0.01 MB/sec
Concurrency:		       96.02

```

배포기간 동안 Availability 가 변화없기 때문에 무정지 재배포가 성공한 것으로 확인됨.

# 신규 개발 조직의 추가

![image](https://user-images.githubusercontent.com/487999/79684133-1d6c4300-826a-11ea-94a2-602e61814ebf.png)

## 마케팅팀의 추가

    - KPI: 신규 고객의 유입률 증대와 기존 고객의 충성도 향상
    - 구현계획 마이크로 서비스: 기존 customer 마이크로 서비스를 인수하며, 고객에 음식 및 맛집 추천 서비스 등을 제공할 예정

## 이벤트 스토밍

    ![image](https://user-images.githubusercontent.com/487999/79685356-2b729180-8273-11ea-9361-a434065f2249.png)

## 헥사고날 아키텍처 변화

![image](https://user-images.githubusercontent.com/487999/79685243-1d704100-8272-11ea-8ef6-f4869c509996.png)

## 구현

기존의 마이크로 서비스에 수정을 발생시키지 않도록 Inbund 요청을 REST 가 아닌 Event 를 Subscribe 하는 방식으로 구현. 기존 마이크로 서비스에 대하여 아키텍처나 기존 마이크로 서비스들의 데이터베이스 구조와 관계없이 추가됨.

## 운영과 Retirement

Request/Response 방식으로 구현하지 않았기 때문에 서비스가 더이상 불필요해져도 Deployment 에서 제거되면 기존 마이크로 서비스에 어떤 영향도 주지 않음.

- [비교] 결제 (pay) 마이크로서비스의 경우 API 변화나 Retire 시에 app(주문) 마이크로 서비스의 변경을 초래함:

예) API 변화시

```
# Order.java (Entity)

    @PostPersist
    public void onPostPersist(){

        fooddelivery.external.결제이력 pay = new fooddelivery.external.결제이력();
        pay.setOrderId(getOrderId());

        Application.applicationContext.getBean(fooddelivery.external.결제이력Service.class)
                .결제(pay);

                -->

        Application.applicationContext.getBean(fooddelivery.external.결제이력Service.class)
                .결제2(pay);

    }
```

예) Retire 시

```
# Order.java (Entity)

    @PostPersist
    public void onPostPersist(){

        /**
        fooddelivery.external.결제이력 pay = new fooddelivery.external.결제이력();
        pay.setOrderId(getOrderId());

        Application.applicationContext.getBean(fooddelivery.external.결제이력Service.class)
                .결제(pay);

        **/
    }
```
