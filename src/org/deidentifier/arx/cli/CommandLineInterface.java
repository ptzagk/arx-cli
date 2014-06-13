/*
 * ARX: Efficient, Stable and Optimal Data Anonymization
 * Copyright (C) 2012 - 2014 Florian Kohlmayer, Fabian Prasser
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.deidentifier.arx.cli;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import org.deidentifier.arx.ARXAnonymizer;
import org.deidentifier.arx.ARXConfiguration;
import org.deidentifier.arx.ARXResult;
import org.deidentifier.arx.AttributeType.Hierarchy;
import org.deidentifier.arx.Data;
import org.deidentifier.arx.DataSelector;
import org.deidentifier.arx.DataSubset;
import org.deidentifier.arx.DataType;
import org.deidentifier.arx.criteria.DPresence;
import org.deidentifier.arx.criteria.DistinctLDiversity;
import org.deidentifier.arx.criteria.EntropyLDiversity;
import org.deidentifier.arx.criteria.EqualDistanceTCloseness;
import org.deidentifier.arx.criteria.HierarchicalDistanceTCloseness;
import org.deidentifier.arx.criteria.KAnonymity;
import org.deidentifier.arx.criteria.PrivacyCriterion;
import org.deidentifier.arx.criteria.RecursiveCLDiversity;

/**
 * A simple command-line client
 * 
 * @author Fabian Prasser
 * @author Florian Kohlmayer
 * 
 */
public class CommandLineInterface {

    /**
     * --quasiidentifying [attributname1,attributname2,...]
     * -qi
     * 
     * --sensitive [attributname1,attributname2,...]
     * -se
     * 
     * --insensitive [attributname1,attributname2,...]
     * -is
     * 
     * --identifying [attributname1,attributname2,...]
     * -id
     * 
     * --hierarchies [attributname1=filename1,attributname2=filename2]
     * -h
     * 
     * --datatype [attributname1=STRING|DECIMAL(format)|INTEGER|DATE(format)]
     * -d
     * 
     * --criteria [x-ANONYMITY,(x,y)-PRESENCE,attributname1=DISTINCT|ENTROPY|RECURSIVE-(x|x,y)-DIVERSITY,attributname2=HIERARCHICAL|EQUALDISTANCE-(x)-CLOSENESS]
     * -c
     * 
     * --metric [DM|DMSTAR|ENTROPY|HEIGHT|NMENTROPY|PREC|AECS]
     * -m
     * 
     * --suppression [value]
     * -s
     * 
     * --database [TYPE=[MYSQL|POSTGRESQL|SQLLITE],URL=value,PORT=value,USER=value,PASSWORD=value,DATABASE=value,TABLE=value]
     * -db
     * 
     * --file [filename]
     * -f
     * 
     * --output [filename]
     * -o
     * 
     * --researchsubset [FILE=filename|QUERY=querystring]
     * -r
     * 
     * --separator [char|DETECT]
     * -sep
     * 
     * --practicalmonotonicity [TRUE|FALSE]
     * -pm
     * 
     * 
     */

    public static final char SEPARATOR_OPTION    = ',';
    public static final char SEPARATOR_KEY_VALUE = '=';
    public static final char SEPARATOR_CRITERIA  = ';';

    public static enum Metric {
        AECS,
        DM,
        DMSTAR,
        ENTROPY,
        HEIGHT,
        NMENTROPY,
        PREC
    }

    /**
     * Lets do it!.
     * 
     * @param args the arguments
     */
    public static void main(final String[] args) {
        final CommandLineInterface cli = new CommandLineInterface();
        cli.run(args);
    }

    /**
     * Splits the splitString by means of the separator. Escaping via backslash allowed, the escape character will be removed.
     * 
     * @param splitString
     * @param separator
     * @return
     */
    private String[] splitBySeparator(final String splitString, final char separator) {
        if (splitString != null && splitString.length() > 0) {
            char escape = '\\';
            String regex = "(?<!" + Pattern.quote(String.valueOf(escape)) + ")" + Pattern.quote(String.valueOf(separator));
            String[] splittedString = splitString.split(regex);
            for (int i = 0; i < splittedString.length; i++) {
                splittedString[i] = splittedString[i].replaceAll(Pattern.quote(String.valueOf(escape)) + Pattern.quote(String.valueOf(separator)), String.valueOf(separator));
            }
            return splittedString;
        } else {
            return new String[0];
        }
    }

    /**
     * Build the data object needed for the ARXAnonymizer. Takes a file and a database string as input.
     * If the file is not null, the data object will be created from the given file, using the given separator as separator.
     * If the file is null and the database string is not null, the data object will be created from the given database.
     * If both, file and database string are null, STDIN will be used for creating the data object.
     * 
     * @param input
     * @param database
     * @param separator
     * @return
     * @throws IOException
     */
    private Data buildDataObject(final File input, final String database, final char separator) throws IOException {
        // build data object

        // TODO: for DataSource a addAllCoulumns could be introduced
        Data data = null;

        if (input != null) { // read from file
            data = Data.create(input, separator);
        } else if ((database != null) && (database.length() > 0)) { // read from db
            // TODO: Implement JDBC datasource
            throw new UnsupportedOperationException("import from database currently not supported");
        } else { // read from console
            // format as CSV!
            data = Data.create(System.in, separator);
        }
        return data;
    }

    /**
     * Creates the list of privacy criteria from the given option string.
     * 
     * @param criteriaOption
     * @param hierarchies
     * @param subset
     * @return
     */
    private List<PrivacyCriterion> parseCriteria(final List<String> criteriaOption, final Map<String, Hierarchy> hierarchies, final DataSubset subset) {
        final List<PrivacyCriterion> criteria = new ArrayList<PrivacyCriterion>();

        final String matchContentInParenthesis = "\\((.*?)\\)";
        final String matchContentInParenthesisCommaSepareted = "\\((.*?)" + SEPARATOR_CRITERIA + "(.*?)\\)";
        final String k_anonymityRegEx = "(\\d)-ANONYMITY";
        final String d_presenceRegEx = matchContentInParenthesisCommaSepareted + "-PRESENCE";
        final String l_diversityRegEx = "-" + matchContentInParenthesis + "-DIVERSITY";
        final String l_diversityRegEx_Recursive = "-" + matchContentInParenthesisCommaSepareted + "-DIVERSITY";
        final String t_closenessRegEx = "-" + matchContentInParenthesis + "-CLOSENESS";

        for (String criterionString : criteriaOption) {

            String key = null;
            String value = null;

            String[] split = splitBySeparator(criterionString, SEPARATOR_KEY_VALUE);
            if (split.length == 1) {
                value = split[0];
            } else if (split.length == 2) {
                key = split[0];
                value = split[1];
            } else {
                throw new IllegalArgumentException("criteria string is malformed.");
            }

            Pattern pattern = null;
            Matcher matcher = null;

            // add all k-anonymity criteria
            pattern = Pattern.compile(k_anonymityRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final int k = Integer.parseInt(matcher.group(1));
                final PrivacyCriterion criterion = new KAnonymity(k);
                criteria.add(criterion);
            }

            // add all d-presence criteria
            pattern = Pattern.compile(d_presenceRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                if (subset == null) {
                    throw new IllegalArgumentException("for d-presence a subset has to be defined");
                }
                final double dmin = Double.parseDouble(matcher.group(1));
                final double dmax = Double.parseDouble(matcher.group(2));
                final PrivacyCriterion criterion = new DPresence(dmin, dmax, subset);
                criteria.add(criterion);
            }

            // add all l-diversity criteria

            // distinct
            pattern = Pattern.compile("DISTINCT" + l_diversityRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final int l = Integer.parseInt(matcher.group(1));
                final PrivacyCriterion criterion = new DistinctLDiversity(key, l);
                criteria.add(criterion);
            }

            // entropy
            pattern = Pattern.compile("ENTROPY" + l_diversityRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final double l = Double.parseDouble(matcher.group(1));
                final PrivacyCriterion criterion = new EntropyLDiversity(key, l);
                criteria.add(criterion);
            }

            // recursive
            pattern = Pattern.compile("RECURSIVE" + l_diversityRegEx_Recursive, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final double c = Double.parseDouble(matcher.group(1));
                final int l = Integer.parseInt(matcher.group(2));
                final PrivacyCriterion criterion = new RecursiveCLDiversity(key, c, l);
                criteria.add(criterion);
            }

            // add all t-closeness criteria

            // hierarchical
            pattern = Pattern.compile("HIERARCHICAL" + t_closenessRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final Hierarchy h = hierarchies.get(key);
                if (h == null) {
                    throw new IllegalArgumentException("for hierarchical t-closeness a hierarchy has to be defined: " + key);
                }
                final double t = Double.parseDouble(matcher.group(1));
                final PrivacyCriterion criterion = new HierarchicalDistanceTCloseness(key, t, h);
                criteria.add(criterion);
            }

            // equal
            pattern = Pattern.compile("EQUALDISTANCE" + t_closenessRegEx, Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(value);
            while (matcher.find()) {
                final double t = Double.parseDouble(matcher.group(1));
                final PrivacyCriterion criterion = new EqualDistanceTCloseness(key, t);
                criteria.add(criterion);
            }
        }

        return criteria;
    }

    /**
     * Creates a map from the option string containing the attribute names as keys and the corresponding data types as values.
     * 
     * @param datatypeOption
     * @return
     */
    private Map<String, DataType<?>> parseDataTypes(final List<String> datatypeOption) {
        final Map<String, DataType<?>> datatypes = new HashMap<String, DataType<?>>();
        for (final String type : datatypeOption) {
            final String[] split = splitBySeparator(type, SEPARATOR_KEY_VALUE);
            if (split.length != 2) {
                throw new IllegalArgumentException("datatype string is malformed.");
            }

            final Pattern pattern = Pattern.compile("(\\w+)[(]?(.*)", Pattern.CASE_INSENSITIVE);
            final Matcher matcher = pattern.matcher(split[1]);
            while (matcher.find()) {
                final String datatype = matcher.group(1).toUpperCase();
                final String f = matcher.group(2);
                String format = "";
                if (f.length() > 0) {
                    format = f.substring(0, f.length() - 1);
                }
                switch (datatype) {
                case "STRING":
                    datatypes.put(split[0], DataType.STRING);
                    break;
                case "INTEGER":
                    datatypes.put(split[0], DataType.INTEGER);
                    break;
                case "DECIMAL":
                    datatypes.put(split[0], DataType.createDecimal(format));
                    break;
                case "DATE":
                    datatypes.put(split[0], DataType.createDate(format));
                    break;
                default:
                    throw new IllegalArgumentException("datatype not recognized: " + datatype);
                }
            }
        }
        return datatypes;
    }

    /**
     * Creates a map from the option string containing the attribute names as keys and the corresponding hierarchies as values.
     * 
     * @param hierarchyOption
     * @param seperator
     * @return
     * @throws IOException
     */
    private Map<String, Hierarchy> parseHierarchies(final List<String> hierarchyOption, final char seperator) throws IOException {
        final Map<String, Hierarchy> hierarchies = new HashMap<String, Hierarchy>();

        if (hierarchyOption != null && hierarchyOption.size() > 0) {
            for (String string : hierarchyOption) {
                String[] split = splitBySeparator(string, SEPARATOR_KEY_VALUE);
                if (split.length != 2) {
                    throw new IllegalArgumentException("hierarchy string is malformed.");
                }
                final Hierarchy h = Hierarchy.create(split[1], seperator);
                hierarchies.put(split[0], h);
            }
        }
        return hierarchies;
    }

    /**
     * Parses the separator option and returns the seperator char.
     * 
     * @param separatorOption
     * @return
     */
    private char parseSeparator(final String separatorOption) {
        if (separatorOption.length() == 1) {
            return separatorOption.charAt(0);
        } else if (separatorOption.equalsIgnoreCase("DETECT")) {
            // TODO: Implement automatic detection
            throw new UnsupportedOperationException("automatic detection of seperator is currently not supported.");
        } else {
            throw new IllegalArgumentException("only a single character or the keyword 'DETECT' is allowed");
        }
    }

    /**
     * Parses the subset option and creates the corresponding subset.
     * 
     * 
     * @param subsetOption
     * @param separator
     * @param data
     * @return
     * @throws ParseException
     * @throws IOException
     */
    private DataSubset parseSubset(final String subsetOption, final char separator, final Data data) throws ParseException, IOException {

        DataSubset subset = null;

        if (subsetOption != null) {

            final Pattern pattern = Pattern.compile("(\\w+)=(.*)", Pattern.CASE_INSENSITIVE);
            final Matcher matcher = pattern.matcher(subsetOption);
            while (matcher.find()) {
                final String type = matcher.group(1).toUpperCase();
                final String content = matcher.group(2);

                switch (type) {
                case "FILE":
                    subset = DataSubset.create(data, Data.create(content, separator));
                    break;
                case "QUERY":
                    final DataSelector selector = DataSelector.create(data, content);
                    subset = DataSubset.create(data, selector);
                    break;
                default:
                    throw new IllegalArgumentException("subset specification not recognized: " + type);
                }
            }
        }

        return subset;
    }

    /**
     * Parse the command line and anonymize.
     * 
     * @param args
     */
    private void run(final String[] args) {
        final OptionParser parser = new OptionParser();

        // define options

        final OptionSpec<String> help = parser.acceptsAll(Arrays.asList("?", "help"), "prints the help").withOptionalArg().ofType(String.class);

        // attributes
        final OptionSpec<String> qiOption = parser.acceptsAll(Arrays.asList("qi", "quasiidentifying"), "names of the quasi identifying attributes, delimited by ','").withRequiredArg().ofType(String.class);
        final OptionSpec<String> seOption = parser.acceptsAll(Arrays.asList("se", "sensitive"), "names of the sensitive attributes, delimited by ','").withRequiredArg().ofType(String.class);
        final OptionSpec<String> isOption = parser.acceptsAll(Arrays.asList("is", "insensitive"), "names of the insensitive attributes, delimited by ','").withRequiredArg().ofType(String.class);
        final OptionSpec<String> idOption = parser.acceptsAll(Arrays.asList("id", "identifying"), "names of the identifying attributes, delimited by ','").withRequiredArg().ofType(String.class);

        // hierarchies
        final OptionSpec<String> hierarchyOption = parser.acceptsAll(Arrays.asList("h", "hierarchies"), "hierarchies for the attributes, delimited by ','. Syntax: [attributname1=filename1,attributname2=filename2]").withRequiredArg().ofType(String.class);

        // datatypes
        final OptionSpec<String> dataTypeOption = parser.acceptsAll(Arrays.asList("d", "datatype"), "datatypes of the attributes, delimited by ','. Syntax: [attributname1=STRING|DECIMAL(format)|INTEGER|DATE(format)]").withRequiredArg().ofType(String.class);

        // criteria
        final OptionSpec<String> criteriaOption = parser.acceptsAll(Arrays.asList("c", "criteria"), "anonymization criteria, delimited by ','. Syntax: [x-ANONYMITY,(x,y)-PRESENCE,attributname1=DISTINCT|ENTROPY|RECURSIVE-(x|x,y)-DIVERSITY,attributname2=HIERARCHICAL|EQUALDISTANCE-(x)-CLOSENESS]").withRequiredArg().ofType(String.class);

        // metric
        final OptionSpec<String> metricOption = parser.acceptsAll(Arrays.asList("m", "metric"), "information loss metric, possible values " + Arrays.toString(Metric.values())).withRequiredArg().ofType(String.class).defaultsTo("ENTROPY");

        // suppression
        final OptionSpec<Double> supressionOption = parser.acceptsAll(Arrays.asList("s", "suppression"), "amount of allowed outlier (supression) in percent/100. e.g. 0.5 means 50% outlier allowed").withRequiredArg().ofType(Double.class).defaultsTo(0.0d);

        // database
        final OptionSpec<String> databaseOption = parser.acceptsAll(Arrays.asList("db", "database"), "connection information for importing data from a database table. Syntax: [TYPE=[MYSQL|POSTGRESQL|SQLLITE],URL=value,PORT=value,USER=value,PASSWORD=value,DATABASE=value,TABLE=value] ").withRequiredArg().ofType(String.class);

        // file
        final OptionSpec<File> fileOption = parser.acceptsAll(Arrays.asList("f", "file"), "filename of the input data").withRequiredArg().ofType(File.class);

        // output
        final OptionSpec<File> outputOption = parser.acceptsAll(Arrays.asList("o", "output"), "filename of anonymized output").withRequiredArg().ofType(File.class);

        // research subset
        final OptionSpec<String> researchSubsetOption = parser.acceptsAll(Arrays.asList("r", "researchsubset"), "specification of a research subset, either by specifying a file or a query. Syntax: [FILE=filename|QUERY=querystring]").withRequiredArg().ofType(String.class);

        // separator
        final OptionSpec<String> separatorOption = parser.acceptsAll(Arrays.asList("sep", "separator"), "seperator used in the sepcified files; if omitted ';' is assumed. Syntax: [char|DETECT]").withRequiredArg().ofType(String.class).defaultsTo(";");

        // practical monotonicity
        final OptionSpec<Boolean> practicalOption = parser.acceptsAll(Arrays.asList("pm", "practicalmonotonicity"), "if present, practical monotonicity is assumed").withOptionalArg().ofType(Boolean.class).defaultsTo(false);

        try {
            final OptionSet options = parser.parse(args);

            if (options.has(help)) {
                parser.printHelpOn(System.out);
                System.exit(0);
            }

            final char separator = parseSeparator(options.valueOf(separatorOption));
            final boolean practicalMonotonicity = options.valueOf(practicalOption);
            final Map<String, Hierarchy> hierarchies = parseHierarchies(Arrays.asList(splitBySeparator(options.valueOf(hierarchyOption), SEPARATOR_OPTION)), separator);

            final File input = options.valueOf(fileOption);
            final String database = options.valueOf(databaseOption);

            final Data data = buildDataObject(input, database, separator);

            final DataSubset subset = parseSubset(options.valueOf(researchSubsetOption), separator, data);
            final List<PrivacyCriterion> criteria = parseCriteria(Arrays.asList(splitBySeparator(options.valueOf(criteriaOption), SEPARATOR_OPTION)), hierarchies, subset);

            final File output = options.valueOf(outputOption);

            final double supression = options.valueOf(supressionOption);

            // set metric
            final Metric mValue = Metric.valueOf(options.valueOf(metricOption).trim().toUpperCase());
            org.deidentifier.arx.metric.Metric<?> metric = null;
            switch (mValue) {
            case PREC:
                metric = org.deidentifier.arx.metric.Metric.createPrecisionMetric();
                break;
            case HEIGHT:
                metric = org.deidentifier.arx.metric.Metric.createHeightMetric();
                break;
            case DMSTAR:
                metric = org.deidentifier.arx.metric.Metric.createDMStarMetric();
                break;
            case DM:
                metric = org.deidentifier.arx.metric.Metric.createDMMetric();
                break;
            case ENTROPY:
                metric = org.deidentifier.arx.metric.Metric.createEntropyMetric();
                break;
            case NMENTROPY:
                metric = org.deidentifier.arx.metric.Metric.createNMEntropyMetric();
                break;
            case AECS:
                metric = org.deidentifier.arx.metric.Metric.createAECSMetric();
                break;
            default:
                throw new IllegalArgumentException("metric unknown: " + mValue);
            }

            final List<String> quasiIdentifier = Arrays.asList(splitBySeparator(options.valueOf(qiOption), SEPARATOR_OPTION));
            final List<String> sensitiveAttributes = Arrays.asList(splitBySeparator(options.valueOf(seOption), SEPARATOR_OPTION));
            final List<String> insensitiveAttributes = Arrays.asList(splitBySeparator(options.valueOf(isOption), SEPARATOR_OPTION));
            final List<String> identifyingAttributes = Arrays.asList(splitBySeparator(options.valueOf(idOption), SEPARATOR_OPTION));

            // define qis
            for (final String attributName : quasiIdentifier) {
                if (!hierarchies.containsKey(attributName)) {
                    throw new IllegalArgumentException("quasi identifiers must have a hierarchy specified: " + attributName);
                }
                data.getDefinition().setAttributeType(attributName, hierarchies.get(attributName));
            }

            // define ses
            for (final String attributName : sensitiveAttributes) {
                data.getDefinition().setAttributeType(attributName, org.deidentifier.arx.AttributeType.SENSITIVE_ATTRIBUTE);
            }

            // define is
            for (final String attributName : insensitiveAttributes) {
                data.getDefinition().setAttributeType(attributName, org.deidentifier.arx.AttributeType.INSENSITIVE_ATTRIBUTE);
            }

            // define id
            for (final String attributName : identifyingAttributes) {
                data.getDefinition().setAttributeType(attributName, org.deidentifier.arx.AttributeType.IDENTIFYING_ATTRIBUTE);
            }

            // data types
            final Map<String, DataType<?>> dataTypes = parseDataTypes(Arrays.asList(splitBySeparator(options.valueOf(dataTypeOption), SEPARATOR_OPTION)));
            for (final Entry<String, DataType<?>> entry : dataTypes.entrySet()) {
                data.getDefinition().setDataType(entry.getKey(), entry.getValue());
            }

            // build config
            final ARXConfiguration config = ARXConfiguration.create();
            config.setMaxOutliers(supression);
            config.setPracticalMonotonicity(practicalMonotonicity);
            config.setMetric(metric);

            // set criteria
            for (final PrivacyCriterion criterion : criteria) {
                config.addCriterion(criterion);
            }

            if (output != null) {
                System.out.println("Using the following criteria for anonymization: " + criteria);
            }

            final ARXAnonymizer anonymizer = new ARXAnonymizer();
            final ARXResult result = anonymizer.anonymize(data, config);

            if (output != null) { // save to file
                result.getOutput().save(output, separator);
            } else { // output on console
                final Iterator<String[]> transformed = result.getOutput().iterator();
                while (transformed.hasNext()) {
                    final String[] line = transformed.next();
                    final StringBuilder outline = new StringBuilder();
                    for (int i = 0; i < line.length; i++) {
                        outline.append(line[i]);
                        if (i < (line.length - 1)) {
                            outline.append(separator);
                        }
                    }
                    // outline.append("\n");
                    System.out.println(outline);
                }
            }

        } catch (final Exception e) {
            try {
                System.err.println(e.getLocalizedMessage());
                e.printStackTrace(); // TODO: for debugging only
                parser.printHelpOn(System.out);
                System.exit(1);
            } catch (final IOException e1) {
                e1.printStackTrace();
                System.exit(1);
            }
        }

    }
}
