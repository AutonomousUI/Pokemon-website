package com.example.battlesimulator.repository;

import com.example.battlesimulator.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    boolean existsByUsernameIgnoreCase(String username);
    Optional<UserAccount> findByUsernameIgnoreCase(String username);
}
