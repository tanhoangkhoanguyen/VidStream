package io.github.tanhoangkhoanguyen.vidstream.auth;

import io.github.tanhoangkhoanguyen.vidstream.dataset.DatasetAccess;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Sign-in user: the Box profile plus one {@link DatasetAuthorities} entry per readable dataset. The
 * session keeps them, so access is checked once per sign-in.
 */
@Service
@Profile("web")
class BoxUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  private static final Logger log = LoggerFactory.getLogger(BoxUserService.class);

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> profiles;
  private final DatasetAccess datasets;

  @Autowired
  BoxUserService(DatasetAccess datasets) {
    this(new DefaultOAuth2UserService(), datasets);
  }

  BoxUserService(
      OAuth2UserService<OAuth2UserRequest, OAuth2User> profiles, DatasetAccess datasets) {
    this.profiles = profiles;
    this.datasets = datasets;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    var user = profiles.loadUser(request);
    var authorities = new HashSet<GrantedAuthority>(user.getAuthorities());
    try {
      datasets
          .readableBy(request.getAccessToken().getTokenValue())
          .forEach(name -> authorities.add(DatasetAuthorities.of(name)));
    } catch (RestClientException e) {
      // Fail sign-in: a partial check would hide datasets the user can read.
      log.warn("Box dataset check failed at sign-in", e);
      throw new OAuth2AuthenticationException(
          new OAuth2Error("box_unavailable"), "Box dataset check failed", e);
    }
    var nameAttribute =
        request
            .getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName();
    return new DefaultOAuth2User(authorities, user.getAttributes(), nameAttribute);
  }
}
