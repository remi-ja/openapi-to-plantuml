package com.github.davidmoten.oas3.puml2;

import static com.github.davidmoten.oas3.puml.Util.first;
import static com.github.davidmoten.oas3.puml.Util.nullListToEmpty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.oas3.model.Association;
import com.github.davidmoten.oas3.model.Class;
import com.github.davidmoten.oas3.model.ClassType;
import com.github.davidmoten.oas3.model.Field;
import com.github.davidmoten.oas3.model.Model;
import com.github.davidmoten.oas3.puml.Names;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

public final class PathsHelper {

    private PathsHelper() {
        // prevent instantiation
    }

    public static Model toModel(Names names) {
        return paths(names);
    }

    private static Model paths(Names names) {
        if (names.paths() == null) {
            return Model.EMPTY;
        } else {
            return names.paths() //
                    .entrySet() //
                    .stream() //
                    .map(entry -> toPlantUmlPath(entry.getKey(), //
                            entry.getValue(), names))
                    .reduce(Model.EMPTY, (a, b) -> a.add(b));
        }
    }

    private static Model toPlantUmlPath(String path, PathItem p, Names names) {
        // add method class blocks with HTTP verb and parameters
        // add response lines
        return p.readOperationsMap() //
                .entrySet() //
                .stream() //
                .map(entry -> {
                    Operation operation = entry.getValue();
                    String className = entry.getKey() + " " + path;
                    FieldsWithModel f = toPlantUmlParameters(names, className, operation.getParameters());
                    Model m = new Model(new Class(className, ClassType.METHOD, f.fields));
                    m = m.add(toPlantUmlResponses(names, operation, className));
                    return m.add(toPlantUmlRequestBody(className, operation, names));
                }) //
                .reduce(Model.EMPTY, (a, b) -> a.add(b));
    }

    private static FieldsWithModel toPlantUmlParameters(Names names, String className, List<Parameter> parameters) {
        return nullListToEmpty(parameters) //
                .stream()//
                .map(param -> toPlantUmlParameter(names, className, param)) //
                .reduce(FieldsWithModel.EMPTY, (a, b) -> a.add(b));
    }

    private static final class FieldsWithModel {
        private static final FieldsWithModel EMPTY = new FieldsWithModel(Collections.emptyList(), Model.EMPTY);
        private final List<Field> fields;
        private final Model model;

        FieldsWithModel(List<Field> fields, Model model) {
            this.fields = fields;
            this.model = model;
        }

        FieldsWithModel add(FieldsWithModel f) {
            List<Field> list = new ArrayList<>(fields);
            list.addAll(f.fields);
            return new FieldsWithModel(list, model.add(f.model));
        }
    }

    private static FieldsWithModel toPlantUmlParameter(Names names, String className, Parameter param) {
        String ref = param.get$ref();
        String parameterName = param.getName();
        Boolean required = param.getRequired();
        if (ref != null) {
            // resolve the parameter ref chain so we can get the parameter `name` and
            // `required` fields
            Parameter p = param;
            while (p.get$ref() != null) {
                String r = p.get$ref();
                p = getParameter(names.components(), r);
            }
            // override name with name from last ref
            parameterName = p.getName();
            required = p.getRequired();
        }
        if (ref != null) {
            return new FieldsWithModel(Collections.emptyList(), new Model(Association.from(className)
                    .to(names.refToClassName(ref)).one().propertyOrParameterName(parameterName).build()));
        }
        Optional<Field> field = Optional.empty();
        Model model;
        if (param.getSchema() != null) {
            model = Common.toModelClass(className + "." + parameterName, param.getSchema(), names, ClassType.PARAMETER);
        } else {
            model = Model.EMPTY;
        }
        // TODO else get schema from content

        final String type = Common.getUmlTypeName(param.get$ref(), param.getSchema(), names);
        if (Common.isSimpleType(type)) {
            field = Optional.of(new Field(parameterName, type, type.endsWith("]"), required));
        } else {
            model = model
                    .add(Association.from(className).to(type).one().propertyOrParameterName(parameterName).build());
        }
        return new FieldsWithModel(field.map(x -> Collections.singletonList(x)).orElse(Collections.emptyList()), model);
    }

    private static Parameter getParameter(Components components, String ref) {
        Preconditions.checkNotNull(ref);
        Reference r = new Reference(ref);
        if ("#/components/parameters".equals(r.namespace)) {
            return components.getParameters().get(r.simpleName);
        } else {
            throw new RuntimeException("unexpected");
        }
    }

    private static Model toPlantUmlRequestBody(String className, Operation operation, Names names) {
        RequestBody body = operation.getRequestBody();
        if (body != null) {
            String ref = body.get$ref();
            if (ref != null) {
                return new Model(Association.from(className).to(names.refToClassName(ref)).one().build());
            }
            Content content = body.getContent();
            if (content != null) {
                Entry<String, MediaType> mediaType = first(content).get();
                // use the first content entry
                final String requestBodyClassName;
                final Model model;
                Schema<?> sch = mediaType.getValue().getSchema();
                if (sch != null && sch.get$ref() != null) {
                    requestBodyClassName = names.refToClassName(sch.get$ref());
                    model = Model.EMPTY;
                } else {
                    requestBodyClassName = className + " Request";
                    if (sch == null) {
                        model = Model.EMPTY;
                    } else {
                        model = Common.toModelClass(requestBodyClassName, sch, names, ClassType.REQUEST_BODY);
                    }
                }
                Association a = Association.from(className).to(requestBodyClassName).one().build();
                return model.add(a);
            }
        }
        return Model.EMPTY;
    }

    private static Model toPlantUmlResponses(Names names, Operation operation, String className) {
        return operation //
                .getResponses() //
                .entrySet() //
                .stream() //
                .map(ent -> {
                    String responseCode = ent.getKey();
                    // TODO only using the first content
                    ApiResponse r = ent.getValue();
                    final Model model;
                    if (r.get$ref() != null) {
                        String returnClassName = names.refToClassName(r.get$ref());
                        model = new Model(Association.from(className).to(returnClassName).one()
                                .responseCode(responseCode).build());
                    } else {

                        if (r.getContent() == null) {
                            final String newReturnClassName = className + " " + responseCode;
                            Model m = new Model(
                                    new com.github.davidmoten.oas3.model.Class(newReturnClassName, ClassType.RESPONSE));
                            String returnClassName = newReturnClassName;
                            model = m.add(Association.from(className).to(returnClassName).one()
                                    .responseCode(responseCode).build());
                        } else {
                            Model m = Model.EMPTY;
                            for (Entry<String, MediaType> contentEntry : r.getContent().entrySet()) {
                                String contentType = contentEntry.getKey();
                                final String newReturnClassName = className + " " + responseCode + " " + contentType;
                                MediaType mediaType = contentEntry.getValue();
                                Schema<?> sch = mediaType.getSchema();
                                if (sch == null) {
                                    // TODO
                                } else if (sch.get$ref() != null) {
                                    String returnClassName = names.refToClassName(sch.get$ref());
                                    m = m.add(Association.from(className).to(returnClassName).one()
                                            .responseCode(responseCode).responseContentType(contentType).build());
                                } else {
                                    String returnClassName = newReturnClassName;
                                    m = m.add(Common.toModelClass(returnClassName, sch, names, ClassType.RESPONSE));
                                    m = m.add(Association.from(className).to(returnClassName).one()
                                            .responseCode(responseCode).responseContentType(contentType).build());
                                }

                            }
                            model = m;
                        }
                    }
                    return model;
                }).reduce(Model.EMPTY, (a, b) -> a.add(b));
    }

    private static final class Reference {
        final String namespace;
        final String simpleName;

        Reference(String ref) {
            this.namespace = ref.substring(0, ref.lastIndexOf("/"));
            this.simpleName = ref.substring(ref.lastIndexOf("/") + 1);
        }
    }

}
