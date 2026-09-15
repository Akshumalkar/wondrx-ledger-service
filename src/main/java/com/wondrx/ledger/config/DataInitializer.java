package com.wondrx.ledger.config;

import com.wondrx.ledger.entity.Wallet;
import com.wondrx.ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    public static final UUID DEMO_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final WalletRepository walletRepository;

    public DataInitializer(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    public void run(String... args) {
        if (!walletRepository.existsById(DEMO_USER_ID)) {
            Wallet demoWallet = new Wallet(DEMO_USER_ID, new BigDecimal("1000.00"));
            walletRepository.save(demoWallet);
            log.info("Initialized demo wallet for testing [userId={}, balance=1000.00]", DEMO_USER_ID);
        }
    }
}
