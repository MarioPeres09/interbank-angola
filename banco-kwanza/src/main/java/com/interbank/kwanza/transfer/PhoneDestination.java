package com.interbank.kwanza.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record PhoneDestination(String accountNumber) {
    static String normalize(String phone) {
        String number = phone.replaceAll("[\\s-]", "");
        if (number.matches("9[0-9]{8}")) {
            number = "+244" + number;
        }
        if (!number.matches("[+]2449[0-9]{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Número de telemóvel inválido");
        }
        return number;
    }
}
