package org.keycloak.protocol.oidc4vp;

import com.danubetech.verifiablecredentials.CredentialSubject;
import com.danubetech.verifiablecredentials.VerifiableCredential;
import com.danubetech.verifiablecredentials.jsonld.VerifiableCredentialContexts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.common.util.Time;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperContainerModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCWellKnownProvider;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.utils.OAuth2Code;
import org.keycloak.protocol.oidc.utils.OAuth2CodeParser;
import org.keycloak.protocol.oidc4vp.mappers.OIDC4VPMapper;
import org.keycloak.protocol.oidc4vp.mappers.OIDC4VPMapperFactory;
import org.keycloak.protocol.oidc4vp.model.CredentialIssuerVO;
import org.keycloak.protocol.oidc4vp.model.CredentialOfferURI;
import org.keycloak.protocol.oidc4vp.model.CredentialRequestVO;
import org.keycloak.protocol.oidc4vp.model.CredentialResponseVO;
import org.keycloak.protocol.oidc4vp.model.CredentialsOfferVO;
import org.keycloak.protocol.oidc4vp.model.ErrorResponse;
import org.keycloak.protocol.oidc4vp.model.ErrorType;
import org.keycloak.protocol.oidc4vp.model.FormatObject;
import org.keycloak.protocol.oidc4vp.model.FormatVO;
import org.keycloak.protocol.oidc4vp.model.PreAuthorizedGrantVO;
import org.keycloak.protocol.oidc4vp.model.PreAuthorizedVO;
import org.keycloak.protocol.oidc4vp.model.ProofTypeVO;
import org.keycloak.protocol.oidc4vp.model.ProofVO;
import org.keycloak.protocol.oidc4vp.model.Role;
import org.keycloak.protocol.oidc4vp.model.SupportedCredential;
import org.keycloak.protocol.oidc4vp.model.SupportedCredentialVO;
import org.keycloak.protocol.oidc4vp.model.TokenResponse;
import org.keycloak.protocol.oidc4vp.signing.JWTSigningService;
import org.keycloak.protocol.oidc4vp.signing.LDSigningService;
import org.keycloak.protocol.oidc4vp.signing.SigningServiceException;
import org.keycloak.protocol.oidc4vp.signing.VCSigningService;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.util.DefaultClientSessionContext;
import org.keycloak.urls.UrlType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.keycloak.protocol.oidc4vp.OIDC4VPClientRegistrationProvider.VC_TYPES_PREFIX;
import static org.keycloak.protocol.oidc4vp.model.FormatVO.JWT_VC;
import static org.keycloak.protocol.oidc4vp.model.FormatVO.JWT_VC_JSON;
import static org.keycloak.protocol.oidc4vp.model.FormatVO.JWT_VC_JSON_LD;
import static org.keycloak.protocol.oidc4vp.model.FormatVO.LDP_VC;

/**
 * Realm-Resource to provide functionality for issuing VerifiableCredentials to users, depending on their roles in
 * registered SIOP-2 clients
 */
public class OIDC4VPIssuerEndpoint {

	private static final Logger LOGGER = Logger.getLogger(OIDC4VPIssuerEndpoint.class);
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_DATE_TIME
			.withZone(ZoneId.of(ZoneOffset.UTC.getId()));

	public static final String LD_PROOF_TYPE = "LD_PROOF";
	public static final String CREDENTIAL_PATH = "credential";
	public static final String TYPE_VERIFIABLE_CREDENTIAL = "VerifiableCredential";
	public static final String GRANT_TYPE_PRE_AUTHORIZED_CODE = "urn:ietf:params:oauth:grant-type:pre-authorized_code";
	private static final String ACCESS_CONTROL_HEADER = "Access-Control-Allow-Origin";

	private final KeycloakSession session;
	public static final String SUBJECT_DID = "subjectDid";
	private final AppAuthManager.BearerTokenAuthenticator bearerTokenAuthenticator;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	private final String issuerDid;

	private final Map<FormatVO, VCSigningService> signingServiceMap = new HashMap<>();

	public OIDC4VPIssuerEndpoint(KeycloakSession session,
			String issuerDid,
			String keyPath,
			AppAuthManager.BearerTokenAuthenticator authenticator,
			ObjectMapper objectMapper, Clock clock) {
		this.session = session;
		this.bearerTokenAuthenticator = authenticator;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.issuerDid = issuerDid;
		try {
			var jwtSigningService = new JWTSigningService(keyPath);
			signingServiceMap.put(JWT_VC, jwtSigningService);
		} catch (SigningServiceException e) {
			LOGGER.warn("Was not able to initialize JWT SigningService, jwt credentials are not supported.", e);
		}
		try {
			var ldSigningService = new LDSigningService(keyPath, clock);
			signingServiceMap.put(LDP_VC, ldSigningService);
		} catch (SigningServiceException e) {
			LOGGER.warn("Was not able to initialize LD SigningService, ld credentials are not supported.", e);
		}
	}

	/**
	 * Returns the did used by Keycloak to issue credentials
	 *
	 * @return the did
	 */
	@GET
	@Path("/issuer")
	@Produces(MediaType.TEXT_PLAIN)
	public Response getIssuerDid() {
		return Response.ok().entity(issuerDid).header(ACCESS_CONTROL_HEADER, "*").build();
	}

	/**
	 * Returns a list of types supported by this realm-resource. Will evaluate all registered SIOP-2 clients and return
	 * there supported types. A user can request credentials for all of them.
	 *
	 * @return the list of supported VC-Types by this realm.
	 */
	@GET
	@Path("{issuer-did}/types")
	@Produces(MediaType.APPLICATION_JSON)
	public List<SupportedCredential> getTypes(@PathParam("issuer-did") String issuerDidParam) {
		assertIssuerDid(issuerDidParam);
		UserModel userModel = getUserModel(
				new NotAuthorizedException("Types is only available to authorized users."));

		LOGGER.debugf("User is {}", userModel.getId());

		return getCredentialsFromModels(getClientModelsFromSession());
	}

	// filter the client models for supported verifable credentials
	private List<SupportedCredential> getCredentialsFromModels(List<ClientModel> clientModels) {
		return List.copyOf(clientModels.stream()
				.map(ClientModel::getAttributes)
				.filter(Objects::nonNull)
				.flatMap(attrs -> attrs.entrySet().stream())
				.filter(attr -> attr.getKey().startsWith(VC_TYPES_PREFIX))
				.flatMap(entry -> mapAttributeEntryToSc(entry).stream())
				.collect(Collectors.toSet()));
	}

	// return the current usermodel
	private UserModel getUserModel(WebApplicationException errorResponse) {
		return getAuthResult(errorResponse).getUser();
	}

	// return the current usersession model
	private UserSessionModel getUserSessionModel() {
		return getAuthResult(new BadRequestException(getErrorResponse(ErrorType.INVALID_TOKEN))).getSession();
	}

	private AuthenticationManager.AuthResult getAuthResult() {
		return getAuthResult(new BadRequestException(getErrorResponse(ErrorType.INVALID_TOKEN)));
	}

	// get the auth result from the authentication manager
	private AuthenticationManager.AuthResult getAuthResult(WebApplicationException errorResponse) {
		AuthenticationManager.AuthResult authResult = bearerTokenAuthenticator.authenticate();
		if (authResult == null) {
			throw errorResponse;
		}
		return authResult;
	}

	private UserModel getUserModel() {
		return getUserModel(new BadRequestException(getErrorResponse(ErrorType.INVALID_TOKEN)));
	}

	// assert that the given string is the configured issuer did
	private void assertIssuerDid(String requestedIssuerDid) {
		if (!requestedIssuerDid.equals(issuerDid)) {
			throw new NotFoundException("No such issuer exists.");
		}
	}

	/**
	 * Returns the meta data of the issuer.
	 */
	@GET
	@Path("{issuer-did}/.well-known/openid-credential-issuer")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getIssuerMetadata(@PathParam("issuer-did") String issuerDidParam) {
		LOGGER.info("Retrieve issuer meta data");
		assertIssuerDid(issuerDidParam);

		KeycloakContext currentContext = session.getContext();

		return Response.ok().entity(new CredentialIssuerVO()
						.credentialIssuer(getIssuer())
						.credentialEndpoint(getCredentialEndpoint())
						.credentialsSupported(getSupportedCredentials(currentContext)))
				.header(ACCESS_CONTROL_HEADER, "*").build();
	}

	private String getRealmResourcePath() {
		KeycloakContext currentContext = session.getContext();
		String realm = currentContext.getRealm().getName();
		String backendUrl = currentContext.getUri(UrlType.BACKEND).getBaseUri().toString();
		if (backendUrl.endsWith("/")) {
			return String.format("%srealms/%s", backendUrl, realm);
		}
		return String.format("%s/realms/%s", backendUrl, realm);
	}

	private String getCredentialEndpoint() {
		return getIssuer() + "/" + CREDENTIAL_PATH;
	}

	private String getIssuer() {
		return String.format("%s/%s/%s", getRealmResourcePath(),
				//TODO: issuer path!
				"tbd",
				issuerDid);
	}

	/**
	 * Returns the openid-configuration of the issuer.
	 * OIDC4VCI wallets expect the openid-configuration below the issuers root, thus we provide it here in addition to its standard keycloak path.
	 */
	@GET
	@Path("{issuer-did}/.well-known/openid-configuration")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getOIDCConfig(@PathParam("issuer-did") String issuerDidParam) {
		LOGGER.info("Get OIDC config.");
		assertIssuerDid(issuerDidParam);
		// some wallets use the openid-config well-known to also gather the issuer metadata. In
		// the future(when everyone uses .well-known/openid-credential-issuer), that can be removed.
		Map<String, Object> configAsMap = objectMapper.convertValue(
				new OIDCWellKnownProvider(session, null, false).getConfig(),
				Map.class);

		List<String> supportedGrantTypes = Optional.ofNullable(configAsMap.get("grant_types_supported"))
				.map(grantTypesObject -> objectMapper.convertValue(
						grantTypesObject, new TypeReference<List<String>>() {
						})).orElse(new ArrayList<>());
		// newly invented by OIDC4VCI and supported by this implementation
		supportedGrantTypes.add(GRANT_TYPE_PRE_AUTHORIZED_CODE);
		configAsMap.put("grant_types_supported", supportedGrantTypes);
		configAsMap.put("token_endpoint", getIssuer() + "/token");
		configAsMap.put("credential_endpoint", getCredentialEndpoint());

		FormatObject ldpVC = new FormatObject(new ArrayList<>());
		FormatObject jwtVC = new FormatObject(new ArrayList<>());

		getCredentialsFromModels(session.getContext().getRealm().getClientsStream().collect(Collectors.toList()))
				.forEach(supportedCredential -> {
					if (supportedCredential.getFormat() == LDP_VC) {
						ldpVC.getTypes().add(supportedCredential.getType());
					} else {
						jwtVC.getTypes().add(supportedCredential.getType());
					}
				});
		return Response.ok()
				.entity(configAsMap)
				.header(ACCESS_CONTROL_HEADER, "*")
				.build();
	}

	/**
	 * Provides URI to the OIDC4VCI compliant credentials offer
	 */
	@GET
	@Path("{issuer-did}/credential-offer-uri")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getCredentialOfferURI(@PathParam("issuer-did") String issuerDidParam,
			@QueryParam("type") String vcType, @QueryParam("format") FormatVO format) {

		LOGGER.infof("Get an offer for %s - %s", vcType, format);
		assertIssuerDid(issuerDidParam);
		// workaround to support implementations not differentiating json & json-ld
		if (format == JWT_VC) {
			// validate that the user is able to get the offered credentials
			getClientsOfType(vcType, JWT_VC_JSON);
		} else {
			getClientsOfType(vcType, format);
		}

		SupportedCredential offeredCredential = new SupportedCredential(vcType, format);
		Instant now = clock.instant();
		JsonWebToken token = new JsonWebToken()
				.id(UUID.randomUUID().toString())
				.subject(getUserModel().getId())
				.nbf(now.getEpochSecond())
				//maybe configurable in the future, needs to be short lived
				.exp(now.plus(Duration.of(30, ChronoUnit.SECONDS)).getEpochSecond());
		token.setOtherClaims("offeredCredential", new SupportedCredential(vcType, format));

		String nonce = generateAuthorizationCode();

		AuthenticationManager.AuthResult authResult = getAuthResult();
		UserSessionModel userSessionModel = getUserSessionModel();

		AuthenticatedClientSessionModel clientSession = userSessionModel.
				getAuthenticatedClientSessionByClient(
						authResult.getClient().getId());
		try {
			clientSession.setNote(nonce, objectMapper.writeValueAsString(offeredCredential));
		} catch (JsonProcessingException e) {
			LOGGER.errorf("Could not convert POJO to JSON: %s", e.getMessage());
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_REQUEST));
		}

		CredentialOfferURI credentialOfferURI = new CredentialOfferURI(getIssuer(), nonce);

		LOGGER.infof("Responding with nonce: %s", nonce);
		return Response.ok()
				.entity(credentialOfferURI)
				.header(ACCESS_CONTROL_HEADER, "*")
				.build();

	}

	/**
	 * Provides an OIDC4VCI compliant credentials offer
	 */
	@GET
	@Path("{issuer-did}/credential-offer/{nonce}")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getCredentialOffer(@PathParam("issuer-did") String issuerDidParam,
			@PathParam("nonce") String nonce) {
		LOGGER.infof("Get an offer from issuer %s for nonce %s", issuerDidParam, nonce);
		assertIssuerDid(issuerDidParam);

		OAuth2CodeParser.ParseResult result = parseAuthorizationCode(nonce);

		SupportedCredential offeredCredential;
		try {
			offeredCredential = objectMapper.readValue(result.getClientSession().getNote(nonce),
					SupportedCredential.class);
			LOGGER.infof("Creating an offer for %s - %s", offeredCredential.getType(),
					offeredCredential.getFormat());
			result.getClientSession().removeNote(nonce);
		} catch (JsonProcessingException e) {
			LOGGER.errorf("Could not convert JSON to POJO: %s", e);
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_REQUEST));
		}

		String preAuthorizedCode = generateAuthorizationCodeForClientSession(result.getClientSession());
		CredentialsOfferVO theOffer = new CredentialsOfferVO()
				.credentialIssuer(getIssuer())
				.credentials(List.of(offeredCredential))
				.grants(new PreAuthorizedGrantVO().
						urnColonIetfColonParamsColonOauthColonGrantTypeColonPreAuthorizedCode(
								new PreAuthorizedVO().preAuthorizedCode(preAuthorizedCode)
										.userPinRequired(false)));

		LOGGER.infof("Responding with offer: %s", theOffer);
		return Response.ok()
				.entity(theOffer)
				.header(ACCESS_CONTROL_HEADER, "*")
				.build();
	}

	/**
	 * Token endpoint, as defined by the standard. Allows to exchange the pre-authorized-code with an access-token
	 */
	@POST
	@Path("{issuer-did}/token")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response exchangeToken(@PathParam("issuer-did") String issuerDidParam,
			@FormParam("grant_type") String grantType,
			@FormParam("code") String code,
			@FormParam("pre-authorized_code") String preauth) {
		assertIssuerDid(issuerDidParam);
		LOGGER.infof("Received token request %s - %s - %s.", grantType, code, preauth);

		if (Optional.ofNullable(grantType).map(gt -> !gt.equals(GRANT_TYPE_PRE_AUTHORIZED_CODE))
				.orElse(preauth == null)) {
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_TOKEN));
		}
		// some (not fully OIDC4VCI compatible) wallets send the preauthorized code as an alternative parameter
		String codeToUse = Optional.ofNullable(code).orElse(preauth);

		OAuth2CodeParser.ParseResult result = parseAuthorizationCode(codeToUse);
		AccessToken accessToken = new TokenManager().createClientAccessToken(session,
				result.getClientSession().getRealm(),
				result.getClientSession().getClient(),
				result.getClientSession().getUserSession().getUser(),
				result.getClientSession().getUserSession(),
				DefaultClientSessionContext.fromClientSessionAndScopeParameter(result.getClientSession(),
						OAuth2Constants.SCOPE_OPENID, session));

		String encryptedToken = session.tokens().encodeAndEncrypt(accessToken);
		String tokenType = "bearer";
		long expiresIn = accessToken.getExp() - Time.currentTime();

		LOGGER.infof("Successfully returned the token: %s.", encryptedToken);
		return Response.ok().entity(new TokenResponse(encryptedToken, tokenType, expiresIn, null, null))
				.header(ACCESS_CONTROL_HEADER, "*")
				.build();
	}

	private OAuth2CodeParser.ParseResult parseAuthorizationCode(String codeToUse) throws BadRequestException {
		EventBuilder eventBuilder = new EventBuilder(session.getContext().getRealm(), session,
				session.getContext().getConnection());
		OAuth2CodeParser.ParseResult result = OAuth2CodeParser.parseCode(session, codeToUse,
				session.getContext().getRealm(),
				eventBuilder);
		if (result.isExpiredCode() || result.isIllegalCode()) {
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_TOKEN));
		}
		return result;
	}

	private String generateAuthorizationCode() {
		AuthenticationManager.AuthResult authResult = getAuthResult();
		UserSessionModel userSessionModel = getUserSessionModel();
		AuthenticatedClientSessionModel clientSessionModel = userSessionModel.
				getAuthenticatedClientSessionByClient(authResult.getClient().getId());
		return generateAuthorizationCodeForClientSession(clientSessionModel);
	}

	private String generateAuthorizationCodeForClientSession(AuthenticatedClientSessionModel clientSessionModel) {
		int expiration = Time.currentTime() + clientSessionModel.getUserSession().getRealm().getAccessCodeLifespan();

		String codeId = UUID.randomUUID().toString();
		String nonce = UUID.randomUUID().toString();
		OAuth2Code oAuth2Code = new OAuth2Code(codeId, expiration, nonce, null, null, null, null,
				clientSessionModel.getUserSession().getId());

		return OAuth2CodeParser.persistCode(session, clientSessionModel, oAuth2Code);
	}

	private Response getErrorResponse(ErrorType errorType) {
		return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(errorType.getValue())).build();
	}

	/**
	 * Options endpoint to serve the cors-preflight requests.
	 * Since we cannot know the address of the requesting wallets in advance, we have to accept all origins.
	 */
	@OPTIONS
	@Path("{any: .*}")
	public Response optionCorsResponse() {
		return Response.ok().header(ACCESS_CONTROL_HEADER, "*")
				.header("Access-Control-Allow-Methods", "POST,GET,OPTIONS")
				.header("Access-Control-Allow-Headers", "Content-Type,Authorization")
				.build();
	}

	/**
	 * Returns a verifiable credential of the given type, containing the information and roles assigned to the
	 * authenticated user.
	 * In order to support the often used retrieval method by wallets, the token can also be provided as a
	 * query-parameter. If the parameter is empty, the token is taken from the authorization-header.
	 *
	 * @param vcType type of the VerifiableCredential to be returend.
	 * @param token  optional JWT to be used instead of retrieving it from the header.
	 * @return the vc.
	 */
	@GET
	@Path("{issuer-did}/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response issueVerifiableCredential(@PathParam("issuer-did") String issuerDidParam,
			@QueryParam("type") String vcType, @QueryParam("token") String
			token) {
		LOGGER.debugf("Get a VC of type %s. Token parameter is %s.", vcType, token);
		assertIssuerDid(issuerDidParam);
		if (token != null) {
			// authenticate with the token
			bearerTokenAuthenticator.setTokenString(token);
		}
		return Response.ok().
				entity(getCredential(vcType, LDP_VC)).
				header(ACCESS_CONTROL_HEADER, "*").
				build();
	}

	/**
	 * Requests a credential from the issuer
	 */
	@POST
	@Path("{issuer-did}/" + CREDENTIAL_PATH)
	@Consumes({ "application/json" })
	@Produces({ "application/json" })
	public Response requestCredential(@PathParam("issuer-did") String issuerDidParam,
			CredentialRequestVO credentialRequestVO) {
		assertIssuerDid(issuerDidParam);
		LOGGER.infof("Received credentials request %s.", credentialRequestVO);

		List<String> types = new ArrayList<>(Objects.requireNonNull(Optional.ofNullable(credentialRequestVO.getTypes())
				.orElseGet(() -> {
					try {
						return objectMapper.readValue(credentialRequestVO.getType(), new TypeReference<>() {
						});
					} catch (JsonProcessingException e) {
						LOGGER.warnf("Was not able to read the type parameter: %s", credentialRequestVO.getType(), e);
						return null;
					}
				})));

		// remove the static type
		types.remove(TYPE_VERIFIABLE_CREDENTIAL);

		if (types.size() != 1) {
			LOGGER.infof("Credential request contained multiple types. Req: %s", credentialRequestVO);
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_REQUEST));
		}
		if (credentialRequestVO.getProof() != null) {
			validateProof(credentialRequestVO.getProof());
		}
		FormatVO requestedFormat = credentialRequestVO.getFormat();
		// workaround to support implementations not differentiating json & json-ld
		if (requestedFormat == JWT_VC) {
			requestedFormat = JWT_VC_JSON;
		}

		String vcType = types.get(0);

		CredentialResponseVO responseVO = new CredentialResponseVO();
		// keep the originally requested here.
		responseVO.format(credentialRequestVO.getFormat());

		Object theCredential = getCredential(vcType, credentialRequestVO.getFormat());
		switch (requestedFormat) {
			case LDP_VC -> responseVO.setCredential(theCredential);
			case JWT_VC_JSON -> responseVO.setCredential(theCredential);
			default -> throw new BadRequestException(getErrorResponse(ErrorType.UNSUPPORTED_CREDENTIAL_TYPE));
		}
		return Response.ok().entity(responseVO)
				.header(ACCESS_CONTROL_HEADER, "*").build();
	}

	protected void validateProof(ProofVO proofVO) {
		if (proofVO.getProofType() != ProofTypeVO.JWT) {
			LOGGER.warn("We currently only support JWT proofs.");
			throw new BadRequestException(getErrorResponse(ErrorType.INVALID_OR_MISSING_PROOF));
		}
		//TODO: validate proof
	}

	protected Object getCredential(String vcType, FormatVO format) {
		// do first to fail fast on auth
		UserSessionModel userSessionModel = getUserSessionModel();
		List<ClientModel> clients = getClientsOfType(vcType, format);
		List<OIDC4VPMapper> protocolMappers = getProtocolMappers(clients)
				.stream()
				.map(OIDC4VPMapperFactory::createOIDC4VPMapper)
				.toList();

		var credentialToSign = getVCToSign(protocolMappers, vcType, userSessionModel);

		return switch (format) {
			case LDP_VC -> signingServiceMap.get(LDP_VC).signCredential(credentialToSign);
			case JWT_VC, JWT_VC_JSON_LD, JWT_VC_JSON -> signingServiceMap.get(JWT_VC)
					.signCredential(credentialToSign);
			default -> throw new IllegalArgumentException(
					String.format("Requested format %s is not supported.", format));
		};
	}

	private List<ProtocolMapperModel> getProtocolMappers(List<ClientModel> clientModels) {
		return clientModels.stream()
				.flatMap(ProtocolMapperContainerModel::getProtocolMappersStream)
				.toList();

	}

	@NotNull
	private List<ClientModel> getClientsOfType(String vcType, FormatVO format) {
		LOGGER.debugf("Retrieve all clients of type %s, supporting format %s", vcType, format.toString());

		List<String> formatStrings = switch (format) {
			case LDP_VC -> List.of(LDP_VC.toString());
			case JWT_VC, JWT_VC_JSON -> List.of(JWT_VC.toString(), JWT_VC_JSON.toString());
			case JWT_VC_JSON_LD -> List.of(JWT_VC.toString(), JWT_VC_JSON_LD.toString());

		};

		Optional.ofNullable(vcType).filter(type -> !type.isEmpty()).orElseThrow(() -> {
			LOGGER.info("No VC type was provided.");
			return new BadRequestException("No VerifiableCredential-Type was provided in the request.");
		});

		String prefixedType = String.format("%s%s", VC_TYPES_PREFIX, vcType);
		LOGGER.infof("Looking for client supporting %s with format %s", prefixedType, formatStrings);
		List<ClientModel> vcClients = getClientModelsFromSession().stream()
				.filter(clientModel -> Optional.ofNullable(clientModel.getAttributes())
						.filter(attributes -> attributes.containsKey(prefixedType))
						.filter(attributes -> formatStrings.stream()
								.anyMatch(formatString -> Arrays.asList(attributes.get(prefixedType).split(","))
										.contains(formatString)))
						.isPresent())
				.toList();

		if (vcClients.isEmpty()) {
			LOGGER.infof("No SIOP-2-Client supporting type %s registered.", vcType);
			throw new BadRequestException(getErrorResponse(ErrorType.UNSUPPORTED_CREDENTIAL_TYPE));
		}
		return vcClients;
	}

	@NotNull
	private UserModel getUserFromSession() {
		LOGGER.debugf("Extract user form session. Realm in context is %s.", session.getContext().getRealm());

		UserModel userModel = getUserModel();
		LOGGER.debugf("Authorized user is %s.", userModel.getId());
		return userModel;
	}

	@NotNull
	private List<ClientModel> getClientModelsFromSession() {
		return session.clients().getClientsStream(session.getContext().getRealm())
				.filter(clientModel -> clientModel.getProtocol() != null)
				.filter(clientModel -> clientModel.getProtocol().equals(OIDC4VPLoginProtocolFactory.PROTOCOL_ID))
				.toList();
	}

	@NotNull
	private Role toRolesClaim(ClientRoleModel crm) {
		Set<String> roleNames = crm
				.getRoleModels()
				.stream()
				.map(RoleModel::getName)
				.collect(Collectors.toSet());
		return new Role(roleNames, crm.getClientId());
	}

	@NotNull
	private VerifiableCredential getVCToSign(List<OIDC4VPMapper> protocolMappers, String vcType,
			UserSessionModel userSessionModel) {

		var subjectBuilder = CredentialSubject.builder();

		Map<String, Object> subjectClaims = new HashMap<>();

		protocolMappers
				.forEach(mapper -> mapper.setClaimsForSubject(subjectClaims, userSessionModel));

		LOGGER.infof("Will set %s", subjectClaims);
		subjectBuilder.claims(subjectClaims);

		CredentialSubject subject = subjectBuilder.build();

		var credentialBuilder = VerifiableCredential.builder()
				.types(List.of(vcType))
				.context(VerifiableCredentialContexts.JSONLD_CONTEXT_W3C_2018_CREDENTIALS_V1)
				.id(URI.create(String.format("urn:uuid:%s", UUID.randomUUID())))
				.issuer(URI.create(issuerDid))
				.issuanceDate(Date.from(clock.instant()))
				.credentialSubject(subject);
		// use the mappers after the default
		protocolMappers
				.forEach(mapper -> mapper.setClaimsForCredential(credentialBuilder, userSessionModel));

		// TODO: replace with expiry mapper
		//		optionalMinExpiry
		//				.map(minExpiry -> Clock.systemUTC()
		//						.instant()
		//						.plus(Duration.of(minExpiry, ChronoUnit.MINUTES)))
		//				.map(Date::from)
		//				.ifPresent(credentialBuilder::expirationDate);

		return credentialBuilder.build();
	}

	@NotNull
	private List<String> getClaimsToSet(String credentialType, List<ClientModel> clients) {
		String claims = clients.stream()
				.map(ClientModel::getAttributes)
				.filter(Objects::nonNull)
				.map(Map::entrySet)
				.flatMap(Set::stream)
				// get the claims
				.filter(entry -> entry.getKey().equals(String.format("%s_%s", credentialType, "claims")))
				.findFirst()
				.map(Map.Entry::getValue)
				.orElse("");
		LOGGER.infof("Should set %s for %s.", claims, credentialType);
		return Arrays.asList(claims.split(","));

	}

	@NotNull
	private Optional<Map<String, String>> getAdditionalClaims(List<ClientModel> clients) {
		Map<String, String> additionalClaims = clients.stream()
				.map(ClientModel::getAttributes)
				.filter(Objects::nonNull)
				.map(Map::entrySet)
				.flatMap(Set::stream)
				// only include the claims explicitly intended for vc
				.filter(entry -> entry.getKey().startsWith(OIDC4VPClientRegistrationProvider.VC_CLAIMS_PREFIX))
				.collect(
						Collectors.toMap(
								// remove the prefix before sending it
								entry -> entry.getKey()
										.replaceFirst(OIDC4VPClientRegistrationProvider.VC_CLAIMS_PREFIX, ""),
								// value is taken untouched if its unique
								Map.Entry::getValue,
								// if multiple values for the same key exist, we add them comma separated.
								// this needs to be improved, once more requirements are known.
								(entry1, entry2) -> {
									if (entry1.equals(entry2) || entry1.contains(entry2)) {
										return entry1;
									} else {
										return String.format("%s,%s", entry1, entry2);
									}
								}
						));
		if (additionalClaims.isEmpty()) {
			return Optional.empty();
		} else {
			return Optional.of(additionalClaims);
		}
	}

	private List<SupportedCredentialVO> getSupportedCredentials(KeycloakContext context) {

		return context.getRealm().getClientsStream()
				.flatMap(cm -> cm.getAttributes().entrySet().stream())
				.filter(entry -> entry.getKey().startsWith(VC_TYPES_PREFIX))
				.flatMap(entry -> mapAttributeEntryToScVO(entry).stream())
				.collect(Collectors.toList());

	}

	private List<SupportedCredential> mapAttributeEntryToSc(Map.Entry<String, String> typesEntry) {
		String type = typesEntry.getKey().replaceFirst(VC_TYPES_PREFIX, "");
		Set<FormatVO> supportedFormats = getFormatsFromString(typesEntry.getValue());
		return supportedFormats.stream().map(formatVO -> new SupportedCredential(type, formatVO))
				.toList();
	}

	private List<SupportedCredentialVO> mapAttributeEntryToScVO(Map.Entry<String, String> typesEntry) {
		String type = typesEntry.getKey().replaceFirst(VC_TYPES_PREFIX, "");
		Set<FormatVO> supportedFormats = getFormatsFromString(typesEntry.getValue());
		return supportedFormats.stream().map(formatVO -> {
					String id = buildIdFromType(formatVO, type);
					return new SupportedCredentialVO()
							.id(id)
							.format(formatVO)
							.types(List.of(type))
							.cryptographicBindingMethodsSupported(List.of("did"))
							.cryptographicSuitesSupported(List.of("Ed25519Signature2018"));
				}
		).toList();
	}

	private String buildIdFromType(FormatVO formatVO, String type) {
		return String.format("%s_%s", type, formatVO.toString());
	}

	private Set<FormatVO> getFormatsFromString(String formatString) {
		return Arrays.stream(formatString.split(",")).map(FormatVO::fromString).collect(Collectors.toSet());
	}

	@Getter
	@RequiredArgsConstructor
	private static class ClientRoleModel {
		private final String clientId;
		private final List<RoleModel> roleModels;
	}
}

