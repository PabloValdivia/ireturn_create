/**********************************************************************
* This file is part of iDempiere ERP Open Source                      *
* http://www.idempiere.org                                            *
*                                                                     *
* Copyright (C) Contributors                                          *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
*                                                                     *
* You should have received a copy of the GNU General Public License   *
* along with this program; if not, write to the Free Software         *
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
* MA 02110-1301, USA.                                                 *
*                                                                     *
* Contributors:                                                       *
* - Orlando Curieles - ingeint                                           *
**********************************************************************/
package com.ingeint.process;

import java.math.BigDecimal;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MDocType;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MLocator;
import org.compiere.model.MRMA;
import org.compiere.model.MRMALine;
import org.compiere.model.MUser;
import org.compiere.model.MWarehouse;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.Msg;

/**
 *	RMACreateReturn
 *
 *  @author Orlando Curieles - ingeint -  - http://www.ingeint.com
 */

public class RMACreateReturn extends SvrProcess
{
	/**	Manual Selection		*/
	/** Mandatory AD_Client */
	private int rmaId = 0;
	
	
	@Override

	protected void prepare() 

	{
		rmaId = getRecord_ID();
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++) 
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;
			else
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
		}
	}
	
	@Override
	protected String doIt() throws Exception {

		//	Load Rma	
		MRMA mrma = new MRMA(getCtx(),rmaId,get_TrxName());
		
		if (!mrma.getDocStatus().equalsIgnoreCase("CO"))
			throw new AdempiereException(Msg.getMsg(mrma.getCtx(),"InvoiceCreateDocNotCompleted"));
		if (mrma.get_Value("M_InOut_ID") !=null)
			throw new AdempiereException(Msg.getMsg(getCtx(),"AnReturnCreated"));
		
		//	Create New MInout based on RMA 
		MInOut minout = new MInOut(mrma.getCtx(), 0,mrma.get_TrxName());

		minout.setAD_Org_ID(mrma.getAD_Org_ID());
		minout.setC_BPartner_ID(mrma.getC_BPartner_ID());
		MBPartner bp = new MBPartner(mrma.getCtx(), mrma.getC_BPartner_ID(), mrma.get_TrxName());
		
		// Set Locations
			MBPartnerLocation[] locs = bp.getLocations(false);
			if (locs != null)
			{
				for (int i = 0; i < locs.length; i++)
				{
					if (locs[i].isShipTo())
						minout.setC_BPartner_Location_ID(locs[i].getC_BPartner_Location_ID());
				}
				//	set to first if not set
				if (minout.getC_BPartner_Location_ID() == 0 && locs.length > 0)
					minout.setC_BPartner_Location_ID(locs[0].getC_BPartner_Location_ID());
			}
			if (minout.getC_BPartner_Location_ID() == 0)
				log.log(Level.SEVERE, "Has no To Address: " + bp);

			//	Set Contact
			MUser[] contacts = bp.getContacts(false);
			if (contacts != null && contacts.length > 0)	//	get first User
				minout.setAD_User_ID(contacts[0].getAD_User_ID());
			//  setBPartner
		minout.setM_RMA_ID(mrma.getM_RMA_ID());
		minout.setC_DocType_ID(MDocType.getShipmentReceiptDocType(mrma.getC_DocType_ID()));
		if (mrma.get_Value("M_Warehouse_ID")==null)
			throw new AdempiereException(Msg.getMsg(mrma.getCtx(),"WarehouseReturn"));		
		minout.setM_Warehouse_ID(mrma.get_ValueAsInt("M_WareHouse_ID"));
		minout.setIsSOTrx(mrma.get_ValueAsBoolean("IsSoTrx"));
		minout.setMovementType("C+");

		if (!minout.save())
		{
			throw new AdempiereException(Msg.getMsg(mrma.getCtx(),"CannotCreateReturn"));
		}

		MRMALine lines[] = mrma.getLines(true);                
		for (MRMALine linef : lines)
		{
			if (linef.getM_Product_ID() !=0)
			{
				MInOutLine minoutline = new MInOutLine(minout);
				minoutline.setAD_Org_ID(minout.getAD_Org_ID());
				minoutline.setM_InOut_ID(minout.get_ID());
				minoutline.setM_RMALine_ID(linef.get_ID());
				minoutline.setM_Product_ID(linef.getM_Product_ID());
				minoutline.setQtyEntered(linef.getQty());
				minoutline.setMovementQty(linef.getQty());
				MLocator deflocator = MLocator.getDefault(MWarehouse.get(mrma.getCtx(), mrma.get_ValueAsInt("M_WareHouse_ID")));  
				if(deflocator==null)
					throw new AdempiereException(Msg.getMsg(mrma.getCtx(),"DRP-001"));
				minoutline.setM_Locator_ID(deflocator.get_ID());
				minoutline.setC_UOM_ID(linef.getC_UOM_ID());
				minoutline.setM_AttributeSetInstance_ID(linef.getM_AttributeSetInstance_ID());

				if (!minoutline.save())
				{
					throw new AdempiereException(Msg.getMsg(mrma.getCtx(),"CannotCreateReturnLines"));
				}
			}
		}
		minout.setDocStatus("CO");
		minout.completeIt();
		minout.saveEx();
		minout.load(get_TrxName());
		mrma.set_ValueOfColumn("M_InOut_ID",minout.get_ID());
		mrma.saveEx();
		BigDecimal docno = new BigDecimal(minout.getDocumentNo());
		addBufferLog(minout.getM_InOut_ID(), minout.getCreated(),docno,Msg.getMsg(mrma.getCtx(),"ReturnGenerated"), minout.get_Table_ID(),minout.getM_InOut_ID());
		return null;
	}
}