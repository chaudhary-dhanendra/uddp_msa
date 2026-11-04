package gov.uddp.core.testsupport;

/** A synthetic LGD snapshot CSV: two states, four districts each, the rest sub-districts (no real names). */
public final class SyntheticLgdSnapshot {
    public static final int ROWS = 200;

    private SyntheticLgdSnapshot() {
    }

    public static String csv() {
        StringBuilder csv = new StringBuilder("# asOf=2024-01-01\nentityType,code,name,status,parentType,parentCode\n");
        for (int state = 1; state <= 2; state++) {
            csv.append("STATE,S").append(state).append(",Synthetic State ").append(state).append(",ACTIVE,,\n");
        }
        for (int district = 1; district <= 8; district++) {
            csv.append("DISTRICT,D").append(district).append(",Synthetic District ").append(district)
                    .append(",ACTIVE,STATE,S").append(district <= 4 ? 1 : 2).append('\n');
        }
        for (int sub = 1; sub <= ROWS - 10; sub++) {
            csv.append("SUB_DISTRICT,SD").append(sub).append(",Synthetic Sub-district ").append(sub)
                    .append(",ACTIVE,DISTRICT,D").append(sub % 8 + 1).append('\n');
        }
        return csv.toString();
    }
}
