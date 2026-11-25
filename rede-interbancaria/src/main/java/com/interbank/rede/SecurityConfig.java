package com.interbank.rede;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;

@Configuration
class SecurityConfig {
    static final String KWANZA = "banco-kwanza";
    static final String HORIZONTE = "banco-horizonte";

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/network/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/interbank/credits").hasRole("BANCO")
                        .requestMatchers(HttpMethod.POST, "/api/interbank/aliases/phone").hasRole("BANCO")
                        .requestMatchers(HttpMethod.GET, "/api/interbank/credits/*").hasRole("BANCO")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    InMemoryUserDetailsManager banks(@Value("${rede.credencial-kwanza}") String kwanza,
                                    @Value("${rede.credencial-horizonte}") String horizonte) {
        if (!kwanza.matches("[!-~]{32,64}") || !horizonte.matches("[!-~]{32,64}") || kwanza.equals(horizonte)) {
            throw new IllegalArgumentException("A rede precisa de duas credenciais distintas de 32 a 64 caracteres ASCII sem espaços");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername(KWANZA).password(encoder.encode(kwanza)).roles("BANCO").build(),
                User.withUsername(HORIZONTE).password(encoder.encode(horizonte)).roles("BANCO").build());
    }
}
