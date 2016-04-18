/**
 *  Copyright (C) 2012 Red Hat, Inc (jcasey@redhat.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.commonjava.maven.ext.manip.state;

import org.commonjava.maven.ext.manip.ManipulationException;
import org.commonjava.maven.ext.manip.impl.XMLManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.apache.commons.lang.StringUtils.isNotEmpty;

/**
 * Captures configuration relating to XML manipulation. Used by {@link XMLManipulator}.
 */
public class XMLState
    implements State
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

   /**
     * Property on the command line that handles modifying XML files. The format is
     *
     * -DxmlUpdate=<file>:<xml-xpath-expression>:[<replacement-value>] [,....]
     *
     * 1. If replacement-value is blank it becomes a delete instead of replace.
     * 2. Multiple operations may be fed in via comma separator.
     *
     * TODO: If <file> is blank this should be a wildcard for all files.
     */
    private static final String XML_PROPERTY = "xmlUpdate";

    /**
     * Used to store mappings of old property to new version.
     */
    private final List<XMLOperation> xmlOperations = new ArrayList<>();

    public XMLState( final Properties userProps )
                    throws ManipulationException
    {
        String property = userProps.getProperty( XML_PROPERTY );

        if ( isNotEmpty( property ) )
        {
            final String[] ops = property.split( "," );

            for ( String operation : ops)
            {
                String []components = operation.split( ":", 3 );
                if ( components.length != 3 )
                {
                    throw new ManipulationException( "Unable to parse property " + property );
                }
                xmlOperations.add( new XMLOperation( components[0], components[1], components[2] ) );
            }
            logger.debug ( "Found xmlOperations {} ", xmlOperations );
        }
    }

    /**
     * Enabled ONLY if propertyManagement is provided in the user properties / CLI -D options.
     *
     * @see State#isEnabled()
     */
    @Override
    public boolean isEnabled()
    {
        return xmlOperations != null && !xmlOperations.isEmpty();
    }

    public List<XMLOperation> getXMLOperations()
    {
        return xmlOperations;
    }


    public final static class XMLOperation
    {
        String file;
        String xpath;
        String update;

        public XMLOperation ( String file, String xpath, String update)
        {
            this.file = file;
            this.xpath = xpath;
            this.update = update;
        }

        public String getFile()
        {
            return file;
        }

        public String getXPath()
        {
            return xpath;
        }

        public String getUpdate()
        {
            return update;
        }
    }
}
