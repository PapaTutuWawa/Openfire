/*
 * Copyright (C) 2023 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.server;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

/**
 * Representation of an invalid-key result of the Server Dialback authentication mechanism.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 */
public class ServerDialbackKeyInvalidException extends Exception
{
    private final String from;
    private final String to;

    public ServerDialbackKeyInvalidException(String from, String to)
    {
        super();
        this.from = from;
        this.to = to;
    }

    public String getFrom()
    {
        return from;
    }

    public String getTo()
    {
        return to;
    }

    public Element toXML() {
        final Document outbound = DocumentHelper.createDocument();
        final Element root = outbound.addElement("root");
        root.addNamespace("db", "urn:xmpp:features:dialback");
        final Element result = root.addElement("db:result");
        result.addAttribute("from", from);
        result.addAttribute("to", to);
        result.addAttribute("type", "invalid");

        return result;
    }
}
