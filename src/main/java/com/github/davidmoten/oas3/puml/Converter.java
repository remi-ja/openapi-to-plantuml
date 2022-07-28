package com.github.davidmoten.oas3.puml;

import static com.github.davidmoten.oas3.internal.Util.quote;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.IOUtils;

import com.github.davidmoten.oas3.internal.ComponentsHelper;
import com.github.davidmoten.oas3.internal.Names;
import com.github.davidmoten.oas3.internal.PathsHelper;
import com.github.davidmoten.oas3.internal.Util;
import com.github.davidmoten.oas3.internal.model.Association;
import com.github.davidmoten.oas3.internal.model.AssociationType;
import com.github.davidmoten.oas3.internal.model.Class;
import com.github.davidmoten.oas3.internal.model.ClassType;
import com.github.davidmoten.oas3.internal.model.Inheritance;
import com.github.davidmoten.oas3.internal.model.Model;
import com.github.davidmoten.oas3.internal.model.Relationship;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

public final class Converter {

    private static final String COLON = " : ";
    private static final String SPACE = " ";

    private Converter() {
        // prevent instantiation
    }

    public static String openApiToPuml(InputStream in) throws IOException {
        return openApiToPuml(IOUtils.toString(in, StandardCharsets.UTF_8));
    }

    public static String openApiToPuml(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return openApiToPuml(in);
        }
    }

    /**
     * Quick and dirty approach : merge all dependencies into the main openApi definition, excepting paths.
     * Works properly in simple cases, but can introduce unwanted behaviors,
     * specially if definitions with the same name are present into multiple files
     * @param main file, the primary open api definition
     * @param secondaries files, linked by the main, directly or indirectly
     * @return the puml String
     */
    public static String openApiToPuml(File main, List<File> secondaries) throws IOException {
        if (null == secondaries || secondaries.isEmpty()) {
            return openApiToPuml(main);
        }
        try (InputStream mainInputStream = new BufferedInputStream(Files.newInputStream(main.toPath()))) {
            SwaggerParseResult mainResult = new OpenAPIParser().readContents(IOUtils.toString(mainInputStream, StandardCharsets.UTF_8), null, null);
            secondaries.forEach(file -> {
                try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                    SwaggerParseResult secondaryResult = new OpenAPIParser().readContents(IOUtils.toString(is, StandardCharsets.UTF_8), null, null);
                    secondaryResult.getOpenAPI().getComponents().getSchemas().forEach((s, schema) -> mainResult.getOpenAPI().getComponents().getSchemas().putIfAbsent(s, schema));
                    secondaryResult.getOpenAPI().getComponents().getResponses().forEach((s, response) -> mainResult.getOpenAPI().getComponents().getResponses().putIfAbsent(s, response));
                    secondaryResult.getOpenAPI().getComponents().getRequestBodies().forEach((s, requestBody) -> mainResult.getOpenAPI().getComponents().getRequestBodies().putIfAbsent(s, requestBody));
                    secondaryResult.getOpenAPI().getComponents().getParameters().forEach((s, parameter) -> mainResult.getOpenAPI().getComponents().getParameters().putIfAbsent(s, parameter));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return openApiToPuml(mainResult.getOpenAPI());
        }
    }

    public static String openApiToPuml(String openApi) {
        SwaggerParseResult result = new OpenAPIParser().readContents(openApi, null, null);
        if (result.getOpenAPI() == null) {
            throw new IllegalArgumentException("Not an OpenAPI definition");
        }
        return openApiToPuml(result.getOpenAPI());
    }

    private static String openApiToPuml(OpenAPI a) {

        Names names = new Names(a);
        Model model = ComponentsHelper //
                .toModel(names) //
                .add(PathsHelper.toModel(names));

        return "@startuml" //
                + "\nhide <<" + toStereotype(ClassType.METHOD).get() + ">> circle" //
                + "\nhide <<" + toStereotype(ClassType.RESPONSE).get() + ">> circle" //
                + "\nhide <<" + toStereotype(ClassType.PARAMETER).get() + ">> circle" //
                + "\nhide empty methods" //
                + "\nhide empty fields" //
                // make sure that periods in class names aren't interpreted as namespace
                // separators (which results in recursive boxing)
                + "\nset namespaceSeparator none" //
                + toPlantUml(model) //
                + "\n\n@enduml";
    }

    private static String toPlantUml(Model model) {
        int anonNumber = 0;
        StringBuilder b = new StringBuilder();
        for (Class cls : model.classes()) {
            if (cls.isEnum()) {
                b.append("\n\nenum " + Util.quote(cls.name())
                        + toStereotype(cls.type()).map(x -> " <<" + x + ">>").orElse("") + " {");
                cls.fields().stream().forEach(f -> {
                    b.append("\n  " + f.name());
                });
                b.append("\n}");
            } else {
                b.append("\n\nclass " + Util.quote(cls.name())
                        + toStereotype(cls.type()).map(x -> " <<" + x + ">>").orElse("") + " {");
                cls.fields().stream().forEach(f -> {
                    b.append("\n  {field} " + f.name() + COLON + f.type() + (f.isRequired() ? "" : " {O}"));
                });
                b.append("\n}");
            }
        }
        // add external ref classes
        Set<String> added = new HashSet<>();
        for (Relationship r : model.relationships()) {
            final String to;
            if (r instanceof Association) {
                Association a = (Association) r;
                to = a.to();
            } else {
                Inheritance a = (Inheritance) r;
                to = a.from();
            }
            if (!added.contains(to) && to.contains(Names.NAMESPACE_DELIMITER)) {
                String[] items = to.split(Names.NAMESPACE_DELIMITER);
                String namespace = items[0];
                String clsName = items[1];
                b.append("\n\nclass " + Util.quote(clsName) + " <<" + namespace + ">>" + " {");
                b.append("\n}");
                added.add(to);
            }
        }

        for (Relationship r : model.relationships()) {
            if (r instanceof Association) {
                Association a = (Association) r;

                final String mult = toMultiplicity(a.type());

                final String label;
                final String arrow;
                if (a.responseCode().isPresent()) {
                    arrow = "..>";
                    label = a.responseCode().get() + a.responseContentType()
                            .filter(x -> !"application/json".equalsIgnoreCase(x))
                            .map(x -> SPACE + x).orElse("");
                } else {
                    arrow = "-->";
                    label = a.propertyOrParameterName().orElse("");
                }
                String to = a.to();
                if (to.contains(Names.NAMESPACE_DELIMITER)) {
                    to = to.split(Names.NAMESPACE_DELIMITER)[1];
                }
                b.append("\n\n" + quote(a.from()) + SPACE + arrow + SPACE + quote(mult) + SPACE
                        + quote(to)
                        + (label.equals("") ? "" : SPACE + COLON + SPACE + quote(label)));
            } else {
                Inheritance a = (Inheritance) r;
                String from = a.from();
                if (from.contains(Names.NAMESPACE_DELIMITER)) {
                    from = from.split(Names.NAMESPACE_DELIMITER)[1];
                }
                if (a.propertyName().isPresent() || a.type() != AssociationType.ONE) {
                    String mult = toMultiplicity(a.type());
                    anonNumber++;
                    String diamond = "anon" + anonNumber;
                    b.append("\n\ndiamond " + diamond);
                    b.append("\n\n" + quote(from) + SPACE + "-->" + quote(mult) + SPACE
                            + quote(diamond) + a.propertyName().map(x -> COLON + quote(x)).orElse(""));
                    for (String otherClassName : a.to()) {
                        b.append("\n\n" + quote(otherClassName) + SPACE + "--|>" + SPACE
                                + quote(diamond));
                    }
                } else {
                    for (String otherClassName : a.to()) {
                        b.append("\n\n" + quote(otherClassName) + SPACE + "--|>" + SPACE
                                + quote(a.from()));
                    }
                }
            }
        }
        return b.toString();
    }

    private static String toMultiplicity(AssociationType type) {
        final String mult;
        if (type == AssociationType.ONE) {
            mult = "1";
        } else if (type == AssociationType.ZERO_ONE) {
            mult = "0..1";
        } else {
            mult = "*";
        }
        return mult;
    }

    private static Optional<String> toStereotype(ClassType type) {
        final String result;
        if (type == ClassType.METHOD) {
            result = "Path";
        } else if (type == ClassType.PARAMETER) {
            result = "Parameter";
        } else if (type == ClassType.REQUEST_BODY) {
            result = "RequestBody";
        } else if (type == ClassType.RESPONSE) {
            result = "Response";
        } else {
            result = null;
        }
        return Optional.ofNullable(result);
    }

}
