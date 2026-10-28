package com.taomish.comparedbtool;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

final class Values {
    private Values() {}
    record Change(String classification, boolean accepted, String absoluteDifference, String relativeDifference) {}
    static String normalize(String value, Rules.Column rule, boolean numeric) {
        if (value == null) {
            if (rule.nullAsZero) return "0";
            return rule.nullAsEmpty ? "" : null;
        }
        String result = rule.trim ? value.strip() : value;
        if (rule.caseInsensitive) result = result.toUpperCase(Locale.ROOT);
        if (rule.dateOnly) {
            LocalDate date = date(result);
            if (date != null) result = date.toString();
        }
        if (numeric || rule.numeric || rule.round != null || rule.nullAsZero) {
            BigDecimal number = number(result);
            if (number != null) {
                if (rule.round != null) number = number.setScale(rule.round, RoundingMode.HALF_UP);
                result = number.stripTrailingZeros().toPlainString();
            }
        }
        return result;
    }
    static Change compare(String left, String right, Rules.Column rule, boolean numeric) {
        if (Objects.equals(left, right)) return null;
        String a = normalize(left, rule, numeric), b = normalize(right, rule, numeric);
        boolean accepted = Objects.equals(a, b);
        String classification = "OTHER";
        if (left != null && right != null && left.equalsIgnoreCase(right)) classification = "CASE_ONLY";
        else if (left != null && right != null && left.strip().equals(right.strip())) classification = "WHITESPACE_ONLY";
        else if ((left == null && "".equals(right)) || (right == null && "".equals(left))) classification = "NULL_VS_EMPTY";
        else if ((left == null && zero(right)) || (right == null && zero(left))) classification = "NULL_VS_ZERO";
        BigDecimal x = number(left), y = number(right);
        String abs = "", rel = "";
        if (numeric || rule.numeric || rule.round != null || rule.absTol.signum() > 0 || rule.relTol.signum() > 0) {
            if (x != null && y != null) {
                BigDecimal difference = x.subtract(y).abs();
                BigDecimal denominator = x.abs().max(y.abs());
                BigDecimal relative = denominator.signum() == 0 ? BigDecimal.ZERO : difference.divide(denominator, MathContext.DECIMAL128);
                abs = difference.toPlainString(); rel = relative.toPlainString();
                boolean tolerance = difference.compareTo(rule.absTol) <= 0
                    || difference.compareTo(rule.relTol.multiply(denominator)) <= 0;
                accepted |= tolerance;
                if (classification.equals("OTHER")) {
                    if (x.compareTo(y) == 0) classification = "FORMAT_ONLY";
                    else if (accepted) classification = "ROUNDING";
                    else if (x.signum() != 0 && x.compareTo(y.negate()) == 0) classification = "SIGN_FLIP";
                }
            }
        }
        if (classification.equals("OTHER") && left != null && right != null) {
            LocalDate dl = date(left), dr = date(right);
            if (dl != null && dr != null) {
                if (dl.equals(dr) && accepted) classification = "FORMAT_ONLY";
                else if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(dl, dr)) == 1) classification = "DATE_SHIFT";
            }
        }
        return new Change(classification, accepted, abs, rel);
    }
    private static boolean zero(String value) { BigDecimal number = number(value); return number != null && number.signum() == 0; }
    private static BigDecimal number(String value) {
        if (value == null) return null;
        try { return new BigDecimal(value.strip()); } catch (NumberFormatException e) { return null; }
    }
    private static LocalDate date(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(value.replace(' ', 'T')).toLocalDate(); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate(); } catch (DateTimeParseException ignored) {}
        return null;
    }
}
