package com.interbank.horizonte.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByOwnerIdOrderByIdAsc(long ownerId);

    Optional<Account> findByIdAndOwnerId(long id, long ownerId);

    boolean existsByIdAndOwnerId(long id, long ownerId);
}
