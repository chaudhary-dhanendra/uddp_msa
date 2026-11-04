package gov.uddp.core.identity;

import org.springframework.stereotype.Component;

/** The Verhoeff dihedral check over all digits, the last being the check digit. */
@Component
public class VerhoeffChecksum implements ChecksumValidator {
    private static final int[][] MULTIPLY = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
        {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
        {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] PERMUTE = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
        {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    @Override
    public String name() {
        return "VERHOEFF";
    }

    @Override
    public boolean valid(String digits) {
        if (digits == null || digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int check = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            check = MULTIPLY[check][PERMUTE[i % 8][digit]];
        }
        return check == 0;
    }
}
