/*
Copyright (C) 2009, 2013, 2014 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package org.harctoolbox.irscrutinizer.importer;

import com.neuron.app.tonto.ActionIRCode;
import com.neuron.app.tonto.CCF;
import com.neuron.app.tonto.CCFAction;
import com.neuron.app.tonto.CCFButton;
import com.neuron.app.tonto.CCFChild;
import com.neuron.app.tonto.CCFDevice;
import com.neuron.app.tonto.CCFFrame;
import com.neuron.app.tonto.CCFIRCode;
import com.neuron.app.tonto.CCFPanel;
import com.neuron.app.tonto.ProntoModel;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.harctoolbox.girr.Command;
import org.harctoolbox.girr.GirrException;
import org.harctoolbox.girr.Remote;
import org.harctoolbox.girr.RemoteSet;
import org.harctoolbox.ircore.InvalidArgumentException;
import org.harctoolbox.irscrutinizer.Version;

/**
 * Class for importing Pronto CCF files of the first generation.
 */
public class CcfImporter extends RemoteSetImporter implements IFileImporter {
    public static RemoteSet importCcf(String filename, String creatingUser) throws IOException, ParseException, InvalidArgumentException {
        CcfImporter importer = new CcfImporter();
        importer.load(filename);
        return importer.remoteSet;
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    public static void main(String args[]) {
        try {
            RemoteSet remoteSet = importCcf(args[0], "The Creator");
            System.out.println(remoteSet);
        } catch (IOException | ParseException | InvalidArgumentException ex) {
        }
    }
    private CCF ccf;
    private boolean translateProntoFont = true;

    public CcfImporter() {
        super();
    }
    /**
     * @param translateProntoFont the translateProntoFont to set
     */
    public void setTranslateProntoFont(boolean translateProntoFont) {
        this.translateProntoFont = translateProntoFont;
    }

    @Override
    public String[][] getFileExtensions() {
        return new String[][]{
            new String[]{ "Pronto (classic) save files (*.ccf)", "ccf"},
            new String[]{ "Zip files (*.zip)", "zip"}
        };
    }

    private Remote loadDevice(CCFDevice dev) {
        boolean totalUniqueNames = true;
        String deviceName = dev.getName();
        Map<java.lang.String,Command> commands = new HashMap<>(64);
        for (CCFPanel panel = dev.getFirstPanel(); panel != null; panel = panel.getNextPanel()) {
            String panelName = panel.getName();
            ArrayList<Command> commandList = loadChildren(panel.getChildren(), deviceName, panelName);
            boolean uniqueNames = addCommands(commandList);
            totalUniqueNames = totalUniqueNames && uniqueNames;
            commandList.forEach((command) -> {
                commands.put(command.getName(), command);
            });
        }

        Map<String, String> notes = new HashMap<>(1);
        notes.put("note", "Imported by IrScrutinizer");
        Remote remote = new Remote(new Remote.MetaData(deviceName),
                origin, //java.lang.String comment,
                notes,//"Imported by IrScrutinizer", //java.lang.String notes,
                commands,
                null //java.util.HashMap<java.lang.String,java.util.HashMap<java.lang.String,java.lang.String>> applicationParameters)
        );
        return remote;
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    private ArrayList<Command> loadChildren(CCFChild children[], String deviceName, String panelName) {
        ArrayList<Command> commandList = new ArrayList<>(16);
        for (CCFChild child : children) {
            CCFButton button = child.getButton();
            String ccfString = null;
            if (button != null) {
                boolean has_content = false;
                String buttonName = button.getName();
                if (buttonName == null)
                    buttonName = "";
                CCFAction action[] = button.getActions();
                if (action != null) {
                    int no_ccf = 0;
                    for (CCFAction action1 : action) {
                        if (action1.getActionType() == CCFAction.ACT_IRCODE) {
                            has_content = true;
                            ActionIRCode code = (ActionIRCode) action1;
                            CCFIRCode ir = code.getIRCode();
                            if (ccfString == null)
                                ccfString = ir.getCode();
                            no_ccf++;
                        }
                    }
                    if (no_ccf > 1)
                        System.err.println("Warning: " + no_ccf + " > 1 codes found in button " + buttonName
                                + " in panel " + panelName + ". Ignoring all but the first.");
                }
                if (has_content) {
                    try {
                        Command command = new Command(translateProntoFont ? ProntoIrCode.translateProntoFont(buttonName) : buttonName,
                                deviceName + "/" + panelName, ccfString);
                        commandList.add(command);
                    } catch (GirrException ex) {
                        System.err.println(ex.getMessage());
                    }
                }
            } else {
                CCFFrame frame = child.getFrame();
                ArrayList<Command> childCommands = loadChildren(frame.getChildren(), deviceName, panelName + "/" + frame.getName());
                commandList.addAll(childCommands);
            }
        }
        return commandList;
    }

    @Override
    public void load(Reader reader, String origin) throws IOException, FileNotFoundException, ParseException, InvalidArgumentException {
        dumbLoad(reader, origin, "windows-1252");
    }

    @Override
    public void load(File file, String origin, String charsetName /*ignored */) throws IOException {
        ccf = new CCF(ProntoModel.getModel(ProntoModel.RU890));
        String filename = file.getPath();
        ccf.load(filename);
        load(ccf, origin);
    }

    private void load(CCF ccf, String origin) throws IOException {
        prepareLoad(origin);
        Map<String,Remote> remotes = new HashMap<>(16);

        for (CCFDevice dev = ccf.getFirstDevice(); dev != null; dev = dev.getNextDevice()) {
            Remote remote = loadDevice(dev);
            if (hasCommands(remote))
                remotes.put(remote.getName(), remote);
        }
        for (CCFDevice dev = ccf.getFirstHomeDevice(); dev != null; dev = dev.getNextDevice()) {
            Remote remote = loadDevice(dev);
            if (hasCommands(remote))
                remotes.put(remote.getName(), remote);
        }
        for (CCFDevice dev = ccf.getFirstMacroDevice(); dev != null; dev = dev.getNextDevice()) {
            Remote remote = loadDevice(dev);
            if (hasCommands(remote))
                remotes.put(remote.getName(), remote);
        }
        remoteSet = new RemoteSet(getCreatingUser(),
                 origin, //java.lang.String source,
                 (new Date()).toString(), //java.lang.String creationDate,
                 Version.appName, //java.lang.String tool,
                 Version.version, //java.lang.String toolVersion,
                 null, //java.lang.String tool2,
                 null, //java.lang.String tool2Version,
                 null, //java.lang.String notes,
                 remotes);
    }

    @Override
    public String getFormatName() {
        return "Pronto CCF";
    }
}
