// Copyright 2006, 2008, 2010 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.services;

import java.util.Arrays;
import java.util.Locale;

import org.apache.tapestry5.internal.services.messages.PropertiesFileParserImpl;
import org.apache.tapestry5.ioc.MessageFormatter;
import org.apache.tapestry5.ioc.Messages;
import org.apache.tapestry5.ioc.Resource;
import org.apache.tapestry5.ioc.internal.services.ClasspathURLConverterImpl;
import org.apache.tapestry5.ioc.internal.util.ClasspathResource;
import org.apache.tapestry5.model.ComponentModel;
import org.apache.tapestry5.services.InvalidationEventHub;
import org.apache.tapestry5.services.ValidationMessagesSource;
import org.apache.tapestry5.services.messages.ComponentMessagesSource;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ValidationMessagesSourceImplTest extends Assert
{
    private ValidationMessagesSource source;

    @BeforeClass
    public void setup()
    {
        final Messages mockMessages = new Messages()
        {

            public boolean contains(String key)
            {
                return key.equalsIgnoreCase("braniac");
            }

            public String format(String key, Object... args)
            {
                return null;
            }

            public String get(String key)
            {
                return "Braniac";
            }

            public MessageFormatter getFormatter(String key)
            {
                return null;
            }

        };

        ComponentMessagesSource mockMessagesSource = new ComponentMessagesSource()
        {

            public Messages getMessages(ComponentModel componentModel, Locale locale)
            {
                return null;
            }

            public InvalidationEventHub getInvalidationEventHub()
            {
                return null;
            }

            public Messages getApplicationCatalog(Locale locale)
            {
                return mockMessages;
            }
        };

        Resource rootResource = new ClasspathResource("/");
        source = new ValidationMessagesSourceImpl(Arrays.asList("org/apache/tapestry5/internal/ValidationMessages",
                "org/apache/tapestry5/internal/ValidationTestMessages"), rootResource, new PropertiesFileParserImpl(),
                mockMessagesSource, new ClasspathURLConverterImpl());
    }

    @Test
    public void builtin_message()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        assertEquals(messages.format("required", "My Field"), "You must provide a value for My Field.");
    }

    /** TAP5-424 */
    @Test
    public void application_catalog_override()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        assertTrue(messages.contains("braniac"));

        assertEquals(messages.get("braniac"), "Braniac");
    }

    @Test
    public void overriden_message()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        assertEquals(messages.get("number-format-exception"), "Number Format Exception");
    }

    @Test
    public void nonlocalized_override()
    {
        Messages messages = source.getValidationMessages(Locale.FRANCE);

        assertEquals(messages.get("number-format-exception"), "Number Format Exception");
    }

    @Test
    public void contributed_message()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        assertEquals(messages.get("contributed"), "This message was contributed inside ValidationTestMessages.");
    }

    @Test
    public void localization_of_message()
    {
        Messages messages = source.getValidationMessages(Locale.FRENCH);

        assertEquals(messages.get("contributed"), "Zees eez Cohntributahd.");
    }

    @Test
    public void contains()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        assertEquals(messages.contains("required"), true);
        assertEquals(messages.contains("contributed"), true);
        assertEquals(messages.contains("this-key-does-not-exist-anywhere"), false);
    }

    @Test
    public void message_formatter()
    {
        Messages messages = source.getValidationMessages(Locale.ENGLISH);

        MessageFormatter formatter = messages.getFormatter("required");

        assertEquals(formatter.format("My Field"), "You must provide a value for My Field.");
    }

    @Test
    public void messages_instances_are_cached()
    {
        Messages english = source.getValidationMessages(Locale.ENGLISH);
        Messages french = source.getValidationMessages(Locale.FRENCH);

        assertSame(source.getValidationMessages(Locale.ENGLISH), english);
        assertSame(source.getValidationMessages(Locale.FRENCH), french);
        assertNotSame(french, english);
    }
}
