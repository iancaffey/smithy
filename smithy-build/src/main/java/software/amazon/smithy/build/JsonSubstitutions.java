/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.build;

import java.util.Map;
import java.util.regex.Pattern;
import software.amazon.smithy.model.Pair;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;

/**
 * Finds string set in a Node object string value and replaces them with a
 * corresponding Node.
 *
 * <p>Each key represents a string to search for, and each value represents
 * what to replace the string with. A value can be any type of Node, allowing
 * for strings to be changed to objects, arrays, etc. Partial string matches
 * are not currently supported. Each replacement string must match the
 * following regular expression: {@code ^[A-Za-z_][A-Za-z_0-9-]+$} (this
 * constraint could potentially be relaxed in the future to allow for
 * substring replacements from string to string).
 *
 * <p>For example, given the following values to replace:
 *
 * <p>{@code {"FOO": {"bar": "baz"}}}
 *
 * <p>and the following Node value:
 *
 * <p>{@code {"hello": "FOO", "baz": "do not replace FOO"}},
 *
 * <p>the resulting Node will become:
 *
 * <p>{@code {"hello": {"bar: "baz"}, "baz": "do not replace FOO"}}.
 *
 * <p>Notice that "do not replace FOO" was not modified because the entire
 * string did not literally match the string "FOO".
 */
public final class JsonSubstitutions {
    private static final Pattern SUBSTITUTIONS_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z_0-9-]+$");
    private final Map<String, Node> findAndReplace;

    private JsonSubstitutions(Map<String, Node> findAndReplace) {
        for (var key : findAndReplace.keySet()) {
            if (!SUBSTITUTIONS_KEY_PATTERN.matcher(key).find()) {
                throw new SmithyBuildException(String.format(
                        "JSON substitution key found named `%s`, but each key must match the following regular "
                        + "expression: %s", key, SUBSTITUTIONS_KEY_PATTERN.pattern()));
            }
        }

        this.findAndReplace = Map.copyOf(findAndReplace);
    }

    /**
     * Creates a substitutions instance from an ObjectNode.
     *
     * @param node ObjectNode used to create the instance.
     * @return Returns the created JsonSubstitutions.
     */
    public static JsonSubstitutions create(ObjectNode node) {
        return create(node.getStringMap());
    }

    /**
     * Creates a substitutions instance from a Map.
     *
     * @param map Map used to create the instance.
     * @return Returns the created JsonSubstitutions.
     */
    public static JsonSubstitutions create(Map<String, Node> map) {
        return new JsonSubstitutions(map);
    }

    /**
     * Replaces strings in the given node.
     *
     * @param node Node to update.
     * @return Returns the updated node.
     */
    public Node apply(Node node) {
        return node.accept(new SubstitutionVisitor()).expectObjectNode();
    }

    private final class SubstitutionVisitor extends NodeVisitor.Default<Node> {
        @Override
        protected Node getDefault(Node value) {
            return value;
        }

        @Override
        public Node arrayNode(ArrayNode node) {
            return node.getElements().stream().map(element -> element.accept(this)).collect(ArrayNode.collect());
        }

        @Override
        public Node objectNode(ObjectNode node) {
            return node.getMembers().entrySet().stream()
                    .map(entry -> Pair.of(entry.getKey(), entry.getValue().accept(this)))
                    .collect(ObjectNode.collect(Pair::getLeft, Pair::getRight));
        }

        @Override
        public Node stringNode(StringNode node) {
            return findAndReplace.getOrDefault(node.getValue(), node);
        }
    }
}
