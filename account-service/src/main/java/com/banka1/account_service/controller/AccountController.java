package com.banka1.account_service.controller;

import com.banka1.account_service.dto.request.PaymentDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import com.banka1.account_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/internal/accounts")
@PreAuthorize("hasRole('SERVICE')")
public class AccountController {

    private AccountService accountService;
//    @PutMapping("/debit/{accountNumber}")
//    public ResponseEntity<UpdatedBalanceResponseDto> debit(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber, @RequestBody @Valid TransactionDto transactionDto) {
//        return new ResponseEntity<>(accountService.debit(jwt,accountNumber,transactionDto),HttpStatus.OK);
//    }
//    @PutMapping("/credit/{accountNumber}")
//    public ResponseEntity<UpdatedBalanceResponseDto> credit(@AuthenticationPrincipal Jwt jwt,@PathVariable String accountNumber, @RequestBody @Valid TransactionDto transactionDto) {
//        return new ResponseEntity<>(accountService.credit(jwt,accountNumber,transactionDto),HttpStatus.OK);
//    }
    @PostMapping("/transaction")
    public ResponseEntity<UpdatedBalanceResponseDto> transaction(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PaymentDto paymentDto) {
        return new ResponseEntity<>(accountService.transaction(paymentDto),HttpStatus.OK);
    }
    @PostMapping("/transfer")
    public ResponseEntity<UpdatedBalanceResponseDto> transfer(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PaymentDto paymentDto) {
        return new ResponseEntity<>(accountService.transfer(paymentDto),HttpStatus.OK);
    }

}
