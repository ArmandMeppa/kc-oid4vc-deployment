package org.keycloak.protocol.oidc4vp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VCData {
	private VCClaims credentialSubject;
}
