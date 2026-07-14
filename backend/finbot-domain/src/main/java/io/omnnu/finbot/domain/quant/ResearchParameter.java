package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.util.regex.Pattern;

public sealed interface ResearchParameter permits
        ResearchParameter.BooleanValue,
        ResearchParameter.IntegerValue,
        ResearchParameter.FloatingValue,
        ResearchParameter.DecimalParameter,
        ResearchParameter.TextValue {
    Pattern NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,119}");

    String name();

    String valueType();

    record BooleanValue(String name, boolean value) implements ResearchParameter {
        public BooleanValue {
            name = requireName(name);
        }

        @Override
        public String valueType() {
            return "BOOLEAN";
        }
    }

    record IntegerValue(String name, long value) implements ResearchParameter {
        public IntegerValue {
            name = requireName(name);
        }

        @Override
        public String valueType() {
            return "INTEGER";
        }
    }

    record FloatingValue(String name, double value) implements ResearchParameter {
        public FloatingValue {
            name = requireName(name);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("floating parameter value must be finite");
            }
        }

        @Override
        public String valueType() {
            return "FLOATING";
        }
    }

    record DecimalParameter(String name, BigDecimal value) implements ResearchParameter {
        public DecimalParameter {
            name = requireName(name);
            value = DecimalValue.finite(value, "value");
        }

        @Override
        public String valueType() {
            return "DECIMAL";
        }
    }

    record TextValue(String name, String value) implements ResearchParameter {
        public TextValue {
            name = requireName(name);
            value = DomainText.required(value, "value", 2_000);
        }

        @Override
        public String valueType() {
            return "TEXT";
        }
    }

    private static String requireName(String value) {
        var normalized = DomainText.required(value, "parameter name", 120);
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("parameter name has an invalid format");
        }
        return normalized;
    }
}
