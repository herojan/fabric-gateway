package ie.zalando.fabric.gateway.models

import akka.http.scaladsl.model.Uri
import cats.Show
import cats.data.NonEmptyList

object SynchDomain {

  object ComposablePathRegex {
    val CAPTURE_WILDCARD_NAME = "([\\w-]+?)"
    val WILDCARD_NAME         = "$1"
    val LINE_END              = "$"
    val COLON                 = "\\:"
    val STAR                  = "\\*"
    val SLASH                 = "\\/"
    val OPEN_CURLY            = "\\{"
    val CLOSE_CURLY           = "\\}"
  }
  import ComposablePathRegex._

  // Skipper / Ingress models
  object Skipper {
    val ZalandoTokenId = "uid"
  }

  sealed trait SkipperPartial {
    def skipperStringValue(): String

    def divider(): String
  }

  sealed trait SkipperPredicate extends SkipperPartial {
    override def divider(): String = " && "
  }

  sealed trait SkipperFilter extends SkipperPartial {
    override def divider(): String = " -> "
  }

  object PathMatch {
    def formatAsSkipperPath(path: String): String =
      if (path == "/**") {
        """PathSubtree("/")"""
      } else {
        s"""Path("${path
          .replaceAll(SLASH + STAR + CAPTURE_WILDCARD_NAME + SLASH, s"/:$WILDCARD_NAME/")
          .replaceAll(SLASH + STAR + CAPTURE_WILDCARD_NAME + LINE_END, s"/:$WILDCARD_NAME")
          .replaceAll(OPEN_CURLY + CAPTURE_WILDCARD_NAME + CLOSE_CURLY, s":$WILDCARD_NAME")
          .replaceAll(SLASH + STAR + LINE_END, "/:id")
          .replaceAll(SLASH + STAR + SLASH, "/:id/")}")"""
      }
  }

  case class PathMatch(path: String) extends SkipperPredicate {
    val skipperStringValue: String = PathMatch.formatAsSkipperPath(path)
  }

  case class PathSubTreeMatch(path: String) extends SkipperPredicate {
    val skipperStringValue: String = s"""PathSubtree("$path")"""
  }

  case class MethodMatch(verb: HttpVerb) extends SkipperPredicate {
    val skipperStringValue: String = s"""Method("${verb.value}")"""
  }

  case class UidMatch(uids: NonEmptyList[String]) extends SkipperPredicate {
    val skipperStringValue: String = s"${uids.foldLeft("JWTPayloadAnyKV(") { (comb, uid) =>
      val pair = comb match {
        case s if s.endsWith("(") => s""""https://identity.zalando.com/managed-id", "$uid""""
        case _                    => s""", "https://identity.zalando.com/managed-id", "$uid""""
      }

      s"$comb$pair"
    }})"
  }

  case class ClientMatch(svcName: String) extends SkipperPredicate {
    val skipperStringValue: String = s"""JWTPayloadAllKV("sub", "$svcName")"""
  }

  case object HttpTraffic extends SkipperPredicate {
    val skipperStringValue: String = s"""Header("X-Forwarded-Proto", "http")"""
  }

  case object HttpsTraffic extends SkipperPredicate {
    val skipperStringValue: String = s"""Header("X-Forwarded-Proto", "https")"""
  }

  case object NonCustomerRealm extends SkipperFilter {
    val skipperStringValue: String = s"""oauthTokeninfoAnyKV("realm", "/services", "realm", "/employees")"""
  }

  case class RequiredPrivileges(privileges: NonEmptyList[String]) extends SkipperFilter {
    val skipperStringValue: String = s"oauthTokeninfoAllScope(${privileges.map(scope ⇒ s""""$scope"""").toList.mkString(", ")})"
  }

  case class GlobalRouteRateLimit(gatewayName: String,
                                  path: PathMatch,
                                  operation: MethodMatch,
                                  allowedRequests: Int,
                                  period: RateLimitPeriod)
      extends SkipperFilter {
    private val groupName = s"${gatewayName}_${DnsString.formatPath(path.path)}_${operation.verb.value}"

    val skipperStringValue: String =
      s"""clusterClientRatelimit("$groupName", $allowedRequests, "1${period.skipperRepresentation}", "Authorization")"""
  }

  case class GlobalUsersRouteRateLimit(gatewayName: String,
                                       path: PathMatch,
                                       operation: MethodMatch,
                                       allowedRequests: Int,
                                       period: RateLimitPeriod)
      extends SkipperFilter {
    private val groupName = s"${gatewayName}_${DnsString.formatPath(path.path)}_${operation.verb.value}_users"

    val skipperStringValue: String =
      s"""clusterClientRatelimit("$groupName", $allowedRequests, "1${period.skipperRepresentation}", "Authorization")"""
  }

  case class ClientSpecificRouteRateLimit(gatewayName: String,
                                          path: PathMatch,
                                          operation: MethodMatch,
                                          serviceMatch: ClientMatch,
                                          allowedRequests: Int,
                                          period: RateLimitPeriod)
      extends SkipperFilter {
    private val groupName = s"${gatewayName}_${DnsString.formatPath(path.path)}_${operation.verb.value}_${serviceMatch.svcName}"

    val skipperStringValue: String =
      s"""clusterClientRatelimit("$groupName", $allowedRequests, "1${period.skipperRepresentation}", "Authorization")"""
  }

  case class EnableAccessLog(httpCodePrefixes: List[Int] = List(4, 5)) extends SkipperFilter {
    val skipperStringValue: String = s"""enableAccessLog(${httpCodePrefixes.mkString(", ")})"""
  }

  case object FlowId extends SkipperFilter {
    val skipperStringValue: String = """flowId("reuse")"""
  }

  case object ForwardTokenInfo extends SkipperFilter {
    val skipperStringValue: String = """forwardToken("X-TokenInfo-Forward", "uid", "scope", "realm")"""
  }

  case class Status(status: Int) extends SkipperFilter {
    val skipperStringValue: String = s"status($status)"
  }

  case class CorsOrigin(allowedOrigins: Set[Uri]) extends SkipperFilter {
    val skipperStringValue: String = {
      val origins = allowedOrigins
        .map { origin =>
          s""""${origin.copy(scheme = Uri.httpScheme(securedConnection = true))}""""
        }
        .mkString(", ")
      s"""corsOrigin($origins)"""
    }
  }

  case class ResponseHeader(key: String, value: String) extends SkipperFilter {
    val skipperStringValue: String = s"""appendResponseHeader($key, $value)"""
  }

  case object Shunt extends SkipperFilter {
    val skipperStringValue: String = "<shunt>"
  }

  case object HttpRejectMsg extends SkipperFilter {
    val skipperStringValue: String =
      """inlineContent("{\"title\":\"Gateway Rejected\",\"status\":400,\"detail\":\"TLS is required\",\"type\":\"https://cloud.docs.zalando.net/howtos/ingress/#redirect-http-to-https\"}")"""
  }

  case object DefaultRejectMsg extends SkipperFilter {
    val skipperStringValue: String =
      """inlineContent("{\"title\":\"Gateway Rejected\",\"status\":404,\"detail\":\"Gateway Route Not Matched\"}")"""
  }

  case object UnauthorizedRejectMsg extends SkipperFilter {
    val skipperStringValue: String =
      """inlineContent("{\"title\":\"Gateway Rejected\",\"status\":403,\"detail\":\"Illegal attempt to access whitelisted route\"}")"""
  }

  case object AdminAuditing extends SkipperFilter {
    val skipperStringValue: String = """unverifiedAuditLog("https://identity.zalando.com/managed-id")"""
  }

  sealed trait RateLimitPeriod {
    def skipperRepresentation: String
  }

  case object PerMinute extends RateLimitPeriod {
    val skipperRepresentation = "m"
  }

  case object PerHour extends RateLimitPeriod {
    val skipperRepresentation = "h"
  }

  // Fabric Gateway Domain Models
  type NamedIngressDefinitions = Map[String, IngressDefinition]
  type GatewayPathRestrictions = Map[HttpVerb, ActionAuthorizations]
  type GatewayPaths            = Map[PathMatch, PathConfig]

  case class SkipperCustomRoute(predicates: NonEmptyList[SkipperPredicate], filters: NonEmptyList[SkipperFilter])

  object DnsString {
    private val ValidDNSHost =
      "^(([a-zA-Z]|[a-zA-Z][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9])$".r
    private val NonAlphaNumeric = "[^A-Za-z0-9]"

    val DefaultRouteSuffix           = DnsString("-default-404-route")
    val DefaultHttpRejectRouteSuffix = DnsString("-reject-http-route")

    def apply(value: String) = new DnsString(value.toLowerCase)

    def isValidDnsName(name: String): Boolean =
      ValidDNSHost.pattern.matcher(name).matches()

    def fromString(input: String): Option[DnsString] =
      if (isValidDnsName(input)) Some(DnsString(input))
      else None

    def corsPath(verb: HttpVerb, path: PathMatch) =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-cors")

    def userAdminPath(verb: HttpVerb, path: PathMatch) =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-admins")

    def rateLimitedServicePath(verb: HttpVerb, path: PathMatch, svc: String): DnsString =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-rl-service-").concat(dnsCompliantName(svc))

    def rateLimitedUserPath(verb: HttpVerb, path: PathMatch): DnsString =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-rl-users-all")

    def unlimitedUserPath(verb: HttpVerb, path: PathMatch): DnsString =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-users-all")

    def rateLimitedPath(verb: HttpVerb, path: PathMatch) =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-rl-all")

    def unlimitedPath(verb: HttpVerb, path: PathMatch) =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-all")

    def unlimitedServicePath(verb: HttpVerb, path: PathMatch, svc: String): DnsString =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-service-").concat(dnsCompliantName(svc))

    def nonWhitelistedReject(verb: HttpVerb, path: PathMatch): DnsString =
      DnsString(s"-${verb.value}-${formatPath(path.path)}-non-whitelisted")

    def dnsCompliantName(service: String): DnsString =
      DnsString(service.replaceAll(NonAlphaNumeric, ""))

    def formatPath(path: String): String =
      path
        .replaceAll(SLASH + STAR + CAPTURE_WILDCARD_NAME + SLASH, s"-$WILDCARD_NAME-")
        .replaceAll(SLASH + STAR + CAPTURE_WILDCARD_NAME + LINE_END, s"-$WILDCARD_NAME")
        .replaceAll(OPEN_CURLY + CAPTURE_WILDCARD_NAME + CLOSE_CURLY, s"$WILDCARD_NAME")
        .replaceAll(STAR + STAR, "wildcard")
        .replaceAll(STAR, "id")
        .replaceAll(NonAlphaNumeric, "-")
        .drop(1) // Remove leading underscore

    implicit val showDnsString: Show[DnsString] = Show.show(dnsString => dnsString.value)
  }

  case class DnsString private (value: String) {
    def concat(other: DnsString): DnsString =
      DnsString(this.value + other.value)
  }

  case class RouteIdentifier(pathMatch: PathMatch, verb: HttpVerb)

  case class SkipperRouteDefinition(name: DnsString,
                                    predicates: List[SkipperPredicate],
                                    filters: List[SkipperFilter],
                                    customRoute: Option[SkipperCustomRoute],
                                    additionalAnnotations: Map[String, String] = Map.empty)

  case class IngressMetaData(routeDefinition: SkipperRouteDefinition, name: String, namespace: String)

  case class FabricServiceDefinition(host: String, service: String, port: String)
  case class ServiceDescription(name: String,
                                portIdentifier: String = HttpModels.DefaultIngressServiceProtocol,
                                trafficWeight: Option[Int] = None)
  case class IngressBackend(host: String, services: Set[ServiceDescription])

  case class IngressDefinition(hostMappings: Set[IngressBackend], metadata: IngressMetaData)

  case class RateLimitDetails(defaultReqRate: Int, period: RateLimitPeriod, uidSpecific: Map[String, Int])

  sealed trait WhitelistingState
  case object Enabled   extends WhitelistingState
  case object Disabled  extends WhitelistingState
  case object Inherited extends WhitelistingState

  case class WhitelistConfig(services: Set[String], state: WhitelistingState)
  case class EmployeeAccessConfig(employees: Set[String])

  case class CorsConfig(allowedOrigins: Set[Uri], allowedHeaders: Set[String])

  case class ActionAuthorizations(
      requiredPrivileges: NonEmptyList[String],
      rateLimit: Option[RateLimitDetails],
      resourceWhitelistConfig: WhitelistConfig,
      employeeAccessConfig: EmployeeAccessConfig
  )

  case class PathConfig(operations: GatewayPathRestrictions)

  // Gateway CRD models
  sealed trait HttpVerb {
    def value: String
  }

  case object Get      extends HttpVerb { override def value: String = "GET"     }
  case object Head     extends HttpVerb { override def value: String = "HEAD"    }
  case object Options  extends HttpVerb { override def value: String = "OPTIONS" }
  case object Put      extends HttpVerb { override def value: String = "PUT"     }
  case object Post     extends HttpVerb { override def value: String = "POST"    }
  case object Patch    extends HttpVerb { override def value: String = "PATCH"   }
  case object Delete   extends HttpVerb { override def value: String = "DELETE"  }
  case object Connect  extends HttpVerb { override def value: String = "CONNECT" }
  case object MatchAll extends HttpVerb { override def value: String = "*"       }

  trait ServiceProvider
  case class SchemaDefinedServices(serviceMappings: Set[IngressBackend])        extends ServiceProvider
  case class StackSetProvidedServices(hosts: Set[String], stackSetName: String) extends ServiceProvider

  case class GatewaySpec(serviceProvider: ServiceProvider,
                         admins: Set[String],
                         globalWhitelistConfig: WhitelistConfig,
                         corsConfig: Option[CorsConfig],
                         paths: GatewayPaths)

  case class GatewayMeta(name: DnsString, namespace: String)

  case class GatewayStatus(numOwnedIngress: Int, ownedIngress: Set[String])

  case class StackSetIntegrationDetails(annotations: Map[String, String], rules: Set[IngressBackend])
}
