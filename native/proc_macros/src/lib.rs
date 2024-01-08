#![feature(proc_macro_diagnostic)]
extern crate proc_macro;

use proc_macro::TokenStream;

use quote::ToTokens;
use syn::{parse_quote, ImplItem, ItemImpl, ItemStruct};

#[proc_macro_derive(InitDefaultInPlace)]
pub fn derive_init_default_in_place(item: TokenStream) -> TokenStream {
    match syn::parse::<ItemStruct>(item.clone()) {
        Ok(struct_item) => {
            let struct_name = struct_item.ident;

            let mut implementation: ItemImpl = parse_quote! {
                #[automatically_derived]
                impl InitDefaultInPlace for *mut #struct_name {
                    fn init_default_in_place(self) {
                    }
                }
            };

            if let ImplItem::Fn(function) = &mut implementation.items.first_mut().unwrap() {
                for struct_member in struct_item.fields {
                    if let Some(struct_member_name) = struct_member.ident {
                        function.block.stmts.push(parse_quote! {
                            unsafe { addr_of_mut!((*self).#struct_member_name).init_default_in_place(); }
                        });
                    }
                }
            }

            implementation.to_token_stream().into()
        }
        Err(err) => TokenStream::from(err.to_compile_error()),
    }
}
