use std::sync::Mutex;

use yrs::{
    encoding::read::Cursor,
    sync::{
        protocol::{Message, SyncMessage},
        MessageReader,
    },
    updates::decoder::{Decode, DecoderV1},
    updates::encoder::{Encode, Encoder, EncoderV1},
    GetString, ReadTxn, Text, Transact, Update, Xml, XmlElementPrelim, XmlFragment, XmlOut,
    XmlTextPrelim,
};

uniffi::include_scaffolding!("syncbook");

pub struct Greeting;

pub enum BlockKind {
    Paragraph,
    TaskItem,
}

pub struct Block {
    pub id: String,
    pub kind: BlockKind,
    pub text: String,
    pub checked: bool,
}

impl Greeting {
    pub fn new() -> Self {
        Self
    }

    pub fn hello(&self, name: String) -> String {
        format!("Hello, {name} from Rust")
    }
}

pub struct SyncDoc {
    doc: Mutex<yrs::Doc>,
}

impl SyncDoc {
    pub fn new() -> Self {
        Self {
            doc: Mutex::new(yrs::Doc::new()),
        }
    }

    pub fn apply_update(&self, update: Vec<u8>) {
        let update = Update::decode_v1(&update).expect("invalid Yrs update");
        self.doc
            .lock()
            .unwrap()
            .transact_mut()
            .apply_update(update)
            .expect("failed to apply update");
    }

    pub fn state_vector(&self) -> Vec<u8> {
        self.doc.lock().unwrap().transact().state_vector().encode_v1()
    }

    pub fn encode_state_as_update(&self, state_vector: Vec<u8>) -> Vec<u8> {
        let doc = self.doc.lock().unwrap();
        let vector = yrs::StateVector::decode_v1(&state_vector).expect("invalid state vector");
        let update = doc.transact().encode_state_as_update_v1(&vector);
        update
    }

    pub fn handle_message(&self, message: Vec<u8>) -> Vec<Vec<u8>> {
        let mut decoder = DecoderV1::new(Cursor::new(&message));
        let mut reader = MessageReader::new(&mut decoder);
        let doc = self.doc.lock().unwrap();
        let mut replies = Vec::new();

        while let Some(Ok(incoming)) = reader.next() {
            let response = match incoming {
                Message::Sync(SyncMessage::SyncStep1(vector)) => {
                    let update = doc.transact().encode_state_as_update_v1(&vector);
                    Some(Message::Sync(SyncMessage::SyncStep2(update)))
                }
                Message::Sync(SyncMessage::SyncStep2(update))
                | Message::Sync(SyncMessage::Update(update)) => {
                    let update = Update::decode_v1(&update).expect("invalid Yrs update");
                    doc.transact_mut().apply_update(update).expect("failed to apply update");
                    None
                }
                Message::Awareness(_) | Message::AwarenessQuery => None,
                _ => None,
            };
            if let Some(response) = response {
                let mut encoder = EncoderV1::new();
                response.encode(&mut encoder);
                replies.push(encoder.to_vec());
            }
        }

        replies
    }

    pub fn blocks(&self) -> Vec<Block> {
        let doc = self.doc.lock().unwrap();
        let txn = doc.transact();
        let root = doc.get_or_insert_xml_fragment("prosemirror");
        root.successors(&txn)
            .filter_map(|node| match node {
                XmlOut::Element(element) if element.tag().as_ref() == "paragraph" => {
                    Some(Block {
                        id: element_id(&element),
                        kind: BlockKind::Paragraph,
                        text: element_text(&element, &txn),
                        checked: false,
                    })
                }
                XmlOut::Element(element) if element.tag().as_ref() == "taskItem" => {
                    Some(task_block(&element, &txn))
                }
                XmlOut::Element(element) if element.tag().as_ref() == "taskList" => {
                    element.successors(&txn).find_map(|child| match child {
                        XmlOut::Element(item) if item.tag().as_ref() == "taskItem" => {
                            Some(task_block(&item, &txn))
                        }
                        _ => None,
                    })
                }
                _ => None,
            })
            .collect()
    }

    pub fn insert_text(&self, block_id: String, offset: u32, text: String) {
        self.with_text(&block_id, |txn, node| node.insert(txn, offset, &text));
    }

    pub fn delete_text(&self, block_id: String, offset: u32, length: u32) {
        self.with_text(&block_id, |txn, node| node.remove_range(txn, offset, length));
    }

    pub fn set_checked(&self, block_id: String, checked: bool) {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        if let Some(item) = find_element(&doc, txn, &block_id) {
            item.insert_attribute(txn, "checked", checked.to_string());
        }
    }

    pub fn split_block(&self, block_id: String, offset: u32) {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        let Some(current) = find_element(&doc, txn, &block_id) else {
            return;
        };
        let text = element_text(&current, txn);
        let (left, right) = text.split_at(offset as usize);
        current.remove_range(txn, 0, current.len(txn));
        current.push_back(txn, XmlTextPrelim::new(left));
        if let Some(parent) = current.parent().and_then(XmlOut::into_xml_fragment) {
            let tag = current.tag().to_string();
            let inserted = parent.insert(txn, parent.len(txn), XmlElementPrelim::empty(tag));
            inserted.push_back(txn, XmlTextPrelim::new(right));
        }
    }

    pub fn toggle_task_list(&self, block_id: String) {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        let Some(element) = find_element(&doc, txn, &block_id) else {
            return;
        };
        if element.tag().as_ref() == "paragraph" {
            element.insert_attribute(txn, "checked", "false");
        } else if element.tag().as_ref() == "taskItem" {
            element.remove_attribute(txn, &"checked");
        }
    }

    fn with_text<F>(&self, block_id: &str, operation: F)
    where
        F: FnOnce(&mut yrs::TransactionMut<'_>, yrs::XmlTextRef),
    {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        if let Some(element) = find_element(&doc, txn, block_id) {
            if let Some(XmlOut::Text(text)) = element.get(txn, 0) {
                operation(txn, text);
            } else {
                let text = element.push_back(txn, XmlTextPrelim::new(""));
                operation(txn, text);
            }
        }
    }
}

fn find_element<'a>(
    doc: &'a yrs::Doc,
    txn: &'a yrs::TransactionMut<'a>,
    id: &str,
) -> Option<yrs::XmlElementRef> {
    let root = doc.get_or_insert_xml_fragment("prosemirror");
    root.successors(txn).find_map(|node| match node {
        XmlOut::Element(element) if element_id(&element) == id => Some(element),
        _ => None,
    })
}

fn element_text<T: yrs::ReadTxn>(element: &yrs::XmlElementRef, txn: &T) -> String {
    element
        .successors(txn)
        .filter_map(|node| match node {
            XmlOut::Text(text) => Some(text.get_string(txn)),
            _ => None,
        })
        .collect()
}

fn task_block<T: yrs::ReadTxn>(item: &yrs::XmlElementRef, txn: &T) -> Block {
    Block {
        id: element_id(item),
        kind: BlockKind::TaskItem,
        text: element_text(item, txn),
        checked: item
            .get_attribute(txn, "checked")
            .map(|value| value.to_string(txn) == "true")
            .unwrap_or(false),
    }
}

fn element_id(element: &yrs::XmlElementRef) -> String {
    format!("{:?}", yrs::XmlOut::Element(element.clone()).id())
}
