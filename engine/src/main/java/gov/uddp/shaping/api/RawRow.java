package gov.uddp.shaping.api;

import java.util.List;
import java.util.Map;

/** One raw row of a messy source, with values carried down from header or merged cells. */
public record RawRow(String file, String sheet, int row, List<String> cells, Map<String, String> carried) {
}
