package gov.uddp.core.identity;

import org.springframework.stereotype.Component;

/** The Luhn (mod 10) check over all digits, the last being the check digit. */
@Component
public class LuhnChecksum implements ChecksumValidator {
    @Override
    public String name() {
        return "LUHN";
    }

    @Override
    public boolean valid(String digits) {
        if (digits == null || digits.length() < 2 || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
