package io.github.ayushkzz.seatbooking.gateway;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    /**
     * One token bucket per client and route. The route id is part of the key so that the strict
     * limit on creating bookings and the general limit do not drain each other's bucket.
     *
     * <p>Clients are identified by their IP address. Behind a load balancer, switch to the
     * X-Forwarded-For address (XForwardedRemoteAddressResolver) so that all users don't share
     * the balancer's address.
     */
    @Bean
    KeyResolver clientKeyResolver() {
        return exchange -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String client = remote == null ? "unknown" : remote.getAddress().getHostAddress();
            return Mono.just((route == null ? "default" : route.getId()) + ":" + client);
        };
    }
}
