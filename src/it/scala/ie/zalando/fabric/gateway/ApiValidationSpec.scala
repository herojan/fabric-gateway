package ie.zalando.fabric.gateway

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import ie.zalando.fabric.gateway.TestJsonModels.{TestSynchResponse, TestValidationResponse}
import ie.zalando.fabric.gateway.TestUtils.TestData._
import ie.zalando.fabric.gateway.TestUtils._
import ie.zalando.fabric.gateway.service.{IngressDerivationChain, StackSetOperations}
import ie.zalando.fabric.gateway.web.{GatewayWebhookRoutes, OperationalRoutes}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import skuber.k8sInit

import scala.concurrent.duration._

class ApiValidationSpec
    extends FlatSpec
    with MockitoSugar
    with Matchers
    with ScalatestRouteTest
    with OperationalRoutes
    with GatewayWebhookRoutes
    with TestJsonModels
    with BeforeAndAfterEach {

  val kubernetesClient       = k8sInit
  val stackSetOperations     = new StackSetOperations(kubernetesClient)
  val ingressDerivationLogic = new IngressDerivationChain(stackSetOperations)

  var wireMockServer: WireMockServer = _

  override def beforeEach() = {
    wireMockServer = new WireMockServer(
      WireMockConfiguration
        .wireMockConfig()
        .port(8001)
        .withRootDirectory("src/it/resources/wiremock")
    )
    wireMockServer.start()
  }

  override def afterEach() = wireMockServer.stop()

  "Gateway Controller API" should "expose a health endpoints" in {
    Get("/health") ~> operationalRoutes ~> check {
      response.status shouldBe StatusCodes.OK
    }
  }

  it should "return a bad request if you do not post a payload in the synch request" in {
    Post("/synch") ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.BadRequest
    }
  }

  it should "return a bad request if you post an invalid payload in the synch request" in {
    synchRequest(InvalidRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.BadRequest
    }
  }

  it should "return OK for a valid payload" in {
    synchRequest(ValidSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.OK
    }
  }

  it should "return OK for a valid payload with named path parameters" in {
    synchRequest(ValidSynchRequestWithNamedPathParameters.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.OK
    }
  }

  it should "return OK for a valid payload with whitelisting" in {
    synchRequest(ValidWhitelistSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.OK
    }
  }

  it should "return ingresses in the same namespace as the fabric gateway definition" in {
    synchRequest(ValidSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii.forall(_.namespace == "some-namespace") shouldBe true
    }
  }

  it should "sanitize incoming gateway names to ensure that ingress DNS entries can be created" in {
    synchRequest(ValidSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii.forall(_.name.startsWith("my-app-gateway")) shouldBe true
    }
  }

  it should "ensure that all admin routes have extra auditing enabled" in {
    synchRequest(ValidSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii
        .filter(_.name.contains("jblogs"))
        .map(_.filters.get)
        .foreach { filterChain =>
          filterChain should include("""enableAccessLog() -> unverifiedAuditLog("https://identity.zalando.com/managed-id")""")
        }
    }
  }

  it should "add routes for whitelisted users to access a resource without scope checks" in {
    synchRequest(ValidWhitelistSynchRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      val userWhitelistIngress = ingressii
        .find(_.name == "my-app-gateway-post-api-resource-rl-users-all")
        .get

      userWhitelistIngress.predicates.get should include(
        """JWTPayloadAnyKV("https://identity.zalando.com/managed-id", "whitelisted_user")""")
      userWhitelistIngress.filters.get should include(
        """clusterClientRatelimit("my-app-gateway_api-resource_POST_users", 10, "1m", "Authorization")""")
    }
  }

  it should "be able to properly deserialize the request payload" in {
    validationRequest(ValidValidationRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      response.status shouldBe StatusCodes.OK
    }
  }

  it should "send an allow response for a valid payload" in {
    validationRequest(ValidValidationRequest.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val resp = responseAs[TestValidationResponse]
      resp.allowed shouldBe true
    }
  }

  it should "send a disallow response for an invalid payload" in {
    validationRequest(ValidationRequestForNoPaths.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val resp = responseAs[TestValidationResponse]
      resp.allowed shouldBe false
      resp.uid shouldBe "failing-uid-1"
      resp.status.get.reason shouldBe "There must be at least 1 path defined"
    }
  }

  it should "handle the weird empty create state gracefully" in {
    validationRequest(ValidationRequestForInvalidInput.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val resp = responseAs[TestValidationResponse]
      resp.allowed shouldBe false
      resp.uid shouldBe "formatting-errors"
      resp.status.get.reason shouldBe "There must be at least 1 path defined, You must have at least 1 `x-fabric-service` defined, or mark the gateway as `x-service-definition: stackset`"
    }
  }

  it should "successfully validate a stackset integrated resource which has no services defined" in {
    validationRequest(ValidationRequestForValidStacksetIntegration.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val resp = responseAs[TestValidationResponse]
      resp.allowed shouldBe true
    }
  }

  it should "reject a stackset integrated resource which also has a service defined" in {
    validationRequest(ValidationRequestForInvalidServiceDefinition.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val resp = responseAs[TestValidationResponse]
      resp.allowed shouldBe false
      resp.uid shouldBe "failing-uid-service-and-ssint-defined"
      resp.status.get.reason shouldBe "You cannot define services with the `x-fabric-service` key and also set external management using `x-external-service-provider`"
    }
  }

  it should "have an appropriate env var set to point at wiremock" in {
    System.getenv("SKUBER_URL") should not be null
  }

  // Tests were timing out with the mock...
  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  it should "accept a stackset managed resource and create the ingress based on the response from the K8s API" in {
    synchRequest(ValidSynchRequestWithStackSetManagedServices.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii should have length 13
      ingressii.map(_.rules.head.paths.map(_.serviceName)).foreach { backends =>
        backends should contain allOf ("my-test-stackset-svc1", "my-test-stackset-svc2")
      }
    }
  }

  it should "accept a stackset managed resource but create no ingress because the stackset can't be found from the K8s API" in {
    synchRequest(ValidSynchRequestWithNonExistingStackSetManagingServices.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii should have length 0
    }
  }

  it should "accept a stackset managed resource but create no ingress because the stackset exists but doesn't have the traffic key in the status" in {
    synchRequest(ValidSynchRequestWithStackSetManagingServicesButNotTrafficStatus.payload) ~> Route.seal(
      createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      ingressii should have length 0
    }
  }

  it should "Create routes for handling OPTIONS requests when CORS is enabled" in {
    synchRequest(ValidSynchRequestWithCorsEnabled.payload) ~> Route.seal(createRoutesFromDerivations(ingressDerivationLogic)) ~> check {
      val ingressii = responseAs[TestSynchResponse].ingressii
      val corsRoutes = ingressii
        .filter(_.name.contains("cors"))
        .flatMap(_.route)
      corsRoutes.size should be > 0
      corsRoutes should contain theSameElementsAs List(
        """Path("/api/resource") && Method("OPTIONS") && Header("X-Forwarded-Proto", "https") -> enableAccessLog(4, 5) -> status(204) -> flowId("reuse") -> corsOrigin("https://example.com", "https://example-other.com") -> appendResponseHeader("Access-Control-Allow-Methods", "POST, OPTIONS") -> appendResponseHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Flow-Id") -> <shunt>""",
        """Path("/api/resource/:id") && Method("OPTIONS") && Header("X-Forwarded-Proto", "https") -> enableAccessLog(4, 5) -> status(204) -> flowId("reuse") -> corsOrigin("https://example.com", "https://example-other.com") -> appendResponseHeader("Access-Control-Allow-Methods", "GET, PATCH, PUT, OPTIONS") -> appendResponseHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Flow-Id") -> <shunt>""",
        """Path("/events") && Method("OPTIONS") && Header("X-Forwarded-Proto", "https") -> enableAccessLog(4, 5) -> status(204) -> flowId("reuse") -> corsOrigin("https://example.com", "https://example-other.com") -> appendResponseHeader("Access-Control-Allow-Methods", "POST, OPTIONS") -> appendResponseHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Flow-Id") -> <shunt>"""
      )
    }
  }
}
