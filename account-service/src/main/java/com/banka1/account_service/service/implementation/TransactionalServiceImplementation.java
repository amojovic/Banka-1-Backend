package com.banka1.account_service.service.implementation;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.dto.request.PaymentDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import com.banka1.account_service.exception.BusinessException;
import com.banka1.account_service.exception.ErrorCode;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.account_service.service.TransactionalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TransactionalServiceImplementation implements TransactionalService {
    private final AccountRepository accountRepository;

    public void debit(Account account, BigDecimal amount) {
        if(account.getRaspolozivoStanje().compareTo(amount)<0)
            throw new BusinessException(ErrorCode.INSUFFICIENT_FUNDS,ErrorCode.INSUFFICIENT_FUNDS.getTitle());
        if(account.getDnevnaPotrosnja().add(amount).compareTo(account.getDnevniLimit())>0)
            throw new BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED,ErrorCode.DAILY_LIMIT_EXCEEDED.getTitle());
        if(account.getMesecnaPotrosnja().add(amount).compareTo(account.getMesecniLimit())>0)
            throw new BusinessException(ErrorCode.MONTHLY_LIMIT_EXCEEDED,ErrorCode.MONTHLY_LIMIT_EXCEEDED.getTitle());
        account.setStanje(account.getStanje().subtract(amount));
        account.setRaspolozivoStanje(account.getRaspolozivoStanje().subtract(amount));
        account.setDnevnaPotrosnja(account.getDnevnaPotrosnja().add(amount));
        account.setMesecnaPotrosnja(account.getMesecnaPotrosnja().add(amount));
        accountRepository.saveAndFlush(account);
    }
    public void credit(Account account, BigDecimal amount) {
        account.setStanje(account.getStanje().add(amount));
        account.setRaspolozivoStanje(account.getRaspolozivoStanje().add(amount));
        accountRepository.saveAndFlush(account);
    }

    //todo kada napravis bankovni racun sacuvaj bankaAmount tu
    @Transactional
    @Override
    public UpdatedBalanceResponseDto transfer(Account from, Account to,Account bank,PaymentDto paymentDto) {
        debit(from, paymentDto.getFromAmount());
        credit(to, paymentDto.getToAmount());
        credit(bank,paymentDto.getCommission());
        return new UpdatedBalanceResponseDto(from.getStanje(),to.getStanje());
    }


}
